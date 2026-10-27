/**
 * dsh-ide-mcp — an MCP client that connects to the IDE only while it is online.
 *
 * The shipped `@deepseek-ai/dsh-mcp-client` treats a server as a child process
 * it owns: it spawns once, retries a fixed number of times, and then gives up
 * until the harness restarts. That model does not fit an editor the user opens
 * and closes at will, so this plugin owns the connection instead:
 *
 *   · poll the relay port every few seconds
 *   · on the first successful probe, run the MCP handshake and register the
 *     IDE's tools into `ctx.tools`
 *   · when the port disappears, unregister them
 *
 * Registration is reversible: `ctx.tools.register()` returns a disposer, so the
 * model's tool list follows the IDE's real state instead of going stale.
 *
 * Tool definitions are built here rather than through `@deepseek-ai/dsh-tools`:
 * importing that package would install a second copy into the profile, and DSH
 * refuses to load a mismatched version of a core package. `defineTool` performs
 * no branding — it validates arguments and copies fields — so a plain object of
 * the same shape registers identically.
 */

import { appendFileSync, mkdirSync } from 'node:fs'
import { connect } from 'node:net'
import { dirname, join } from 'node:path'

export const name = 'dsh-ide-mcp'

/** Wait for the tool registry before touching `ctx.tools`. */
export const inject = ['tools']

/** IDEA plugin relay, which adds the project header the harness cannot template. */
const ENDPOINT = 'http://127.0.0.1:64343/mcp'

/** Reachability poll cadence. */
const POLL_MS = 3_000

/** TCP probe timeout. */
const PROBE_TIMEOUT_MS = 1_200

/** Per-call budget; IDE operations such as a build can legitimately take minutes. */
const CALL_TIMEOUT_MS = 300_000

/** Prefix keeps these names distinct from native tools and from other servers. */
const NAME_PREFIX = 'mcp__ide__'

/** Protocol revision this client speaks. */
const PROTOCOL_VERSION = '2025-06-18'

/**
 * Tools exposed by default.
 *
 * The IDE publishes 52 tools, including a full debugger surface. Every schema
 * reaches the system prompt, so the debugger set and the database set stay out
 * until something actually needs them. Set `config.all = true` to register
 * everything.
 */
const DEFAULT_TOOLS = [
  'get_all_open_file_paths',
  'list_directory_tree',
  'read_file',
  'search_file',
  'search_text',
  'search_regex',
  'search_symbol',
  'get_symbol_info',
  'rename_refactoring',
  'apply_patch',
  'create_new_file',
  'reformat_file',
  'build_project',
  'get_file_problems',
  'lint_files',
  'get_project_modules',
  'get_project_dependencies',
  'get_run_configurations',
  'execute_run_configuration',
  'open_file_in_editor',
  'git_status',
]

/**
 * The desktop shell swallows stdout, so a file log is the only way to see what
 * this plugin did after a restart. Kept next to the other bridge hand-off files.
 */
function logFile() {
  const base = process.env.LOCALAPPDATA
    || join(process.env.USERPROFILE || process.env.HOME || '.', 'AppData', 'Local')
  return join(base, 'dsh-ide-bridge', 'dsh-mcp.log')
}

function log(message, error) {
  const suffix = error === undefined
    ? ''
    : ` :: ${error?.stack ?? error?.message ?? String(error)}`
  const line = `${new Date().toISOString()} ${message}${suffix}\n`
  try {
    mkdirSync(dirname(logFile()), { recursive: true })
    appendFileSync(logFile(), line)
  } catch {
    // Logging must never take the plugin down.
  }
  console.log('[dsh-ide-mcp]', message)
}

/** Minimal JSON-RPC client for MCP over Streamable HTTP. */
class McpHttpClient {
  constructor(url) {
    this.url = url
    this.sessionId = null
    this.nextId = 1
  }

  async post(method, params) {
    const body = { jsonrpc: '2.0', method }
    const isNotification = method.startsWith('notifications/')
    if (!isNotification) {
      body.id = this.nextId++
    }
    if (params !== undefined) {
      body.params = params
    }

    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'mcp-protocol-version': PROTOCOL_VERSION,
    }
    if (this.sessionId !== null) {
      headers['mcp-session-id'] = this.sessionId
    }

    const response = await fetch(this.url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(CALL_TIMEOUT_MS),
    })

    const session = response.headers.get('mcp-session-id')
    if (session !== null && session.length > 0) {
      this.sessionId = session
    }

    if (!response.ok) {
      throw new Error(`MCP ${method} failed: HTTP ${response.status}`)
    }
    if (isNotification) {
      return undefined
    }

    const contentType = response.headers.get('content-type') ?? ''
    const payload = contentType.includes('text/event-stream')
      ? await readFirstSseFrame(response)
      : await response.json()

    if (payload?.error !== undefined) {
      throw new Error(`MCP ${method} failed: ${payload.error.message ?? 'unknown error'}`)
    }
    return payload?.result
  }

  /** initialize + initialized. Leaves the tool list alone. */
  async handshake() {
    this.sessionId = null
    const initialized = await this.post('initialize', {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 'dsh-ide-mcp', version: '1.1.0' },
    })
    await this.post('notifications/initialized')
    return initialized
  }

  /** Handshake, then collect the server's tool list. */
  async open() {
    const initialized = await this.handshake()
    const listed = await this.post('tools/list')
    return { serverInfo: initialized?.serverInfo ?? {}, tools: listed?.tools ?? [] }
  }

  /** Calls one tool and flattens its content into a single string for the model. */
  async call(name, args) {
    try {
      return await this.callOnce(name, args)
    } catch (error) {
      // A 404 means the server forgot this session (it restarted, or the session
      // aged out). Re-handshake once and retry; tool registration stays as is.
      if (!String(error?.message ?? '').includes('HTTP 404')) {
        throw error
      }
      log(`session expired on ${name}, re-handshaking`)
      await this.handshake()
      return await this.callOnce(name, args)
    }
  }

  async callOnce(name, args) {
    const result = await this.post('tools/call', { name, arguments: args ?? {} })
    const parts = []
    for (const item of result?.content ?? []) {
      if (item?.type === 'text') {
        parts.push(item.text)
      } else if (item?.type === 'image') {
        parts.push(`[image ${item.mimeType ?? ''} omitted]`)
      } else if (typeof item?.text === 'string') {
        parts.push(item.text)
      }
    }
    const text = parts.join('\n').trim()
    if (result?.isError === true) {
      throw new Error(text.length > 0 ? text : 'IDE tool reported an error')
    }
    return text.length > 0 ? text : '(no output)'
  }
}

/** Streamable HTTP may answer a call with an SSE frame carrying the JSON result. */
async function readFirstSseFrame(response) {
  const reader = response.body?.getReader()
  if (reader === undefined) {
    throw new Error('MCP response had no body')
  }
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const boundary = buffer.indexOf('\n\n')
      if (boundary === -1) continue
      const frame = buffer.slice(0, boundary)
      const data = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      if (data.length > 0) {
        return JSON.parse(data)
      }
      buffer = buffer.slice(boundary + 2)
    }
  } finally {
    reader.cancel().catch(() => {})
  }
  throw new Error('MCP event stream ended without a data frame')
}

/** TCP reachability: the relay port is up exactly while the IDE is running. */
function probePort(port) {
  return new Promise((resolve) => {
    const socket = connect({ port, host: '127.0.0.1' })
    let settled = false
    const done = (ok) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolve(ok)
    }
    socket.setTimeout(PROBE_TIMEOUT_MS)
    socket.once('connect', () => done(true))
    socket.once('timeout', () => done(false))
    socket.once('error', () => done(false))
  })
}

/** Model-facing name for one IDE tool. */
function publicName(rawName) {
  return NAME_PREFIX + rawName.replace(/[^A-Za-z0-9_-]/g, '_')
}

/**
 * Builds a registry-ready definition without importing `@deepseek-ai/dsh-tools`.
 * The shape mirrors what that package's `defineTool` returns.
 */
function toToolDefinition(mcpTool, client) {
  const parameters = mcpTool.inputSchema ?? { type: 'object', properties: {} }
  return {
    name: publicName(mcpTool.name),
    description: mcpTool.description ?? `IDE tool ${mcpTool.name}`,
    parameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: String(value) }],
    },
    async execute(args) {
      return client.call(mcpTool.name, args)
    },
  }
}

export function apply(ctx, config = {}) {
  const client = new McpHttpClient(ENDPOINT)
  const wantAll = config.all === true
  const allowed = new Set(wantAll ? [] : (config.tools ?? DEFAULT_TOOLS))

  let disposers = []
  let connected = false
  let ticking = false

  const forget = () => {
    for (const dispose of disposers) {
      try {
        dispose()
      } catch (error) {
        log('cannot unregister a tool', error)
      }
    }
    disposers = []
  }

  const disconnect = (reason) => {
    if (!connected) return
    connected = false
    forget()
    log(`disconnected from the IDE (${reason})`)
  }

  const connectOnce = async () => {
    const { serverInfo, tools } = await client.open()
    const selected = tools.filter((tool) => wantAll || allowed.has(tool.name))
    for (const mcpTool of selected) {
      disposers.push(ctx.tools.register(toToolDefinition(mcpTool, client)))
    }
    connected = true
    log(
      `connected to ${serverInfo.name ?? 'IDE'} ${serverInfo.version ?? ''}, ` +
        `session=${client.sessionId ?? 'none'}, registered ${selected.length}/${tools.length} tools`,
    )
  }

  const tick = async () => {
    if (ticking) return
    ticking = true
    try {
      const online = await probePort(64343)
      if (!online) {
        disconnect('port closed')
        return
      }
      if (!connected) {
        try {
          await connectOnce()
        } catch (error) {
          // The port can accept a connection before the server finishes booting.
          log('handshake failed, will retry', error)
        }
      }
    } finally {
      ticking = false
    }
  }

  ctx.effect(() => {
    log('plugin loaded, watching 64343')
    const timer = setInterval(() => {
      tick().catch((error) => log('poll failed', error))
    }, POLL_MS)
    timer.unref?.()
    tick().catch((error) => log('initial poll failed', error))
    return () => {
      clearInterval(timer)
      disconnect('plugin unloading')
    }
  }, 'dsh-ide-mcp: IDE connection')
}
