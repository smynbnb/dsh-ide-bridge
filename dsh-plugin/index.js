/**
 * dsh-ide-bridge — two jobs for the harness side of the IDE bridge.
 *
 * 1. Publish this process's authenticated web URL.
 *
 *    The desktop shell keeps its browser launch token in memory: `dsh web`
 *    prints `dsh web: http://host:port/?token=...` once at startup, but the
 *    Electron shell swallows that line. A process outside the harness therefore
 *    cannot reconstruct the URL, which is exactly what an IDE plugin needs in
 *    order to attach to the *same* instance instead of spawning a second one.
 *
 *    Contract (shared with the JetBrains plugin's BridgeConfig):
 *      {
 *        "schema": 1,
 *        "url": "http://127.0.0.1:<port>/?token=<launch-token>",
 *        "pid": <harness pid>,
 *        "startedAt": <epoch ms>,
 *        "version": "0.3.0",
 *        "updatedAt": <epoch ms>
 *      }
 *
 * 2. Keep the IDE's project registered as a DSH workspace.
 *
 *    The IDE plugin writes `ide-project.json` with the directory it has open.
 *    Registering that directory makes it the newest workspace, which is what a
 *    freshly created session defaults to — so opening a project in the editor
 *    is enough, no manual workspace picker step.
 */

import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const name = 'dsh-ide-bridge'

/** Bumped together with the reader's BridgeConfig when the contract changes. */
const SCHEMA = 1
const VERSION = '0.3.0'

/** How often the IDE's project file is re-read. */
const PROJECT_POLL_MS = 5_000

function bridgeDirectory() {
  const localAppData = process.env.LOCALAPPDATA
  const base = localAppData && localAppData.length > 0
    ? localAppData
    : join(process.env.USERPROFILE ?? process.env.HOME ?? '.', 'AppData', 'Local')
  return join(base, 'dsh-ide-bridge')
}

/**
 * Resolve the hand-off file path.
 *
 * `%LOCALAPPDATA%` by default: visible to both programs, outside the project
 * tree (so a token never reaches version control), and independent of the
 * user's DSH_HOME.
 */
function resolveTarget(config) {
  if (config && typeof config.path === 'string' && config.path.length > 0) {
    return config.path
  }
  return join(bridgeDirectory(), 'bridge.json')
}

/** The IDE plugin's own hand-off file: `{ projectPath, port, updatedAt }`. */
function projectFile() {
  return join(bridgeDirectory(), 'ide-project.json')
}

function readProjectPath() {
  try {
    const parsed = JSON.parse(readFileSync(projectFile(), 'utf8'))
    if (typeof parsed.projectPath === 'string' && parsed.projectPath.length > 0) {
      return parsed.projectPath.replace(/\\/g, '/')
    }
  } catch {
    // No IDE project published yet.
  }
  return null
}

/** Write through a temp file so a reader never observes a half-written JSON. */
function writeAtomically(target, payload) {
  mkdirSync(dirname(target), { recursive: true })
  const temporary = `${target}.${process.pid}.tmp`
  writeFileSync(temporary, `${JSON.stringify(payload, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  })
  renameSync(temporary, target)
}

function removeQuietly(target) {
  try {
    rmSync(target, { force: true })
  } catch {
    // Already gone is the desired state.
  }
}

/** Same file the MCP client writes, so one log covers the whole bridge. */
function logFile() {
  return join(bridgeDirectory(), 'dsh-mcp.log')
}

function log(message, error) {
  const suffix = error === undefined
    ? ''
    : ` :: ${error?.stack ?? error?.message ?? String(error)}`
  const line = `${new Date().toISOString()} [bridge] ${message}${suffix}\n`
  try {
    mkdirSync(dirname(logFile()), { recursive: true })
    writeFileSync(logFile(), line, { flag: 'a' })
  } catch {
    // Logging must never take the plugin down.
  }
  console.log('[dsh-ide-bridge]', message)
}

export function apply(ctx, config = {}) {
  ctx.inject(['connection', 'webServer'], (webCtx) => {
    const target = resolveTarget(config)
    const startedAt = Date.now()

    const publish = () => {
      const origin = webCtx.webServer.getOrigin()
      const url = webCtx.connection.authenticatedUrl(origin)
      writeAtomically(target, {
        schema: SCHEMA,
        url,
        pid: process.pid,
        startedAt,
        version: VERSION,
        updatedAt: Date.now(),
      })
    }

    webCtx.effect(() => {
      try {
        publish()
      } catch (error) {
        // A missing file only degrades the editor side to "desktop not
        // running"; it must never take the harness down.
        log('cannot publish hand-off file', error)
      }
      return () => removeQuietly(target)
    }, 'dsh-ide-bridge: hand-off file')
  })

  // Register the IDE's project as a workspace. `create` returns the existing
  // record for a known directory, so polling is cheap; only a directory the
  // registry has never seen becomes a new, first-listed workspace.
  ctx.inject(['workspaceRegistry'], (wsCtx) => {
    let lastAttempt = null
    let syncing = false

    const sync = async () => {
      if (syncing) return
      const path = readProjectPath()
      if (path === null || path === lastAttempt) return
      syncing = true
      lastAttempt = path
      try {
        const project = await wsCtx.workspaceRegistry.create(path)
        log(`workspace ready for IDE project: ${project?.path ?? path}`)
      } catch (error) {
        // Retry next tick: the directory may be mid-rename during a checkout.
        lastAttempt = null
        log(`workspace registration failed for ${path}`, error)
      } finally {
        syncing = false
      }
    }

    wsCtx.effect(() => {
      const timer = setInterval(() => {
        sync().catch((error) => log('workspace sync failed', error))
      }, PROJECT_POLL_MS)
      timer.unref?.()
      sync().catch((error) => log('initial workspace sync failed', error))
      return () => clearInterval(timer)
    }, 'dsh-ide-bridge: workspace sync')
  })
}
