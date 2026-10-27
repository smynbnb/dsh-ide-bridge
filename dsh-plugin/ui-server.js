/**
 * dsh-ide-ui — serves the editor panel's single-page chat UI from the harness
 * origin, streams one session's output to it, and turns the panel into the
 * approval answerer for the sessions it owns.
 *
 * Same-origin matters twice: the page inherits the browser authentication
 * cookie, so `/api/*` calls and the `/api/ide/stream` EventSource work without
 * passing a token around, and no CORS surface is opened.
 *
 * Streaming: the panel used to poll `session/page`, which cannot show tokens as
 * they arrive. This plugin instead subscribes to the Host's own event bus
 * (`session/event` for durable records, `agent/assistant-stream` for the dense
 * live frames the model adapter emits) and forwards both over one Server-Sent
 * Events response. SSE is deliberate: it reconnects by itself, needs no
 * hand-written framing, and the Host's HTTP bridge already streams a Response
 * body with backpressure.
 *
 * Approvals: `dsh-user-approval` asks a Cordis waterfall; whoever answers first
 * owns the decision. The Web UI's own answerer forwards each request to a
 * connected browser client, so a panel-only session would otherwise show its
 * permission prompt in the desktop window — or nowhere, if that window is
 * behind the IDE. This plugin prepends one listener that answers only for
 * sessions with a live panel subscription, and delegates everything else with
 * `next()`. Pending requests fail closed (`unavailable`) when the panel goes
 * away, matching the approval seam's own no-answerer behavior.
 *
 * Plan mode is enforced with `tools.guard` rather than prompt wording. A guard
 * runs after the pre-execute waterfall and can only deny, so once the panel
 * reports Plan mode, write and execute tools are refused outright — the model
 * cannot talk its way past it. (Cline's Plan mode relies on system-prompt text
 * alone, which its own issue tracker records as frequently ignored.)
 */

import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const name = 'dsh-ide-ui'

/** Panel page. */
const PAGE_PATH = '/api/ide-panel'

/** Plan-mode state toggle used by the panel. */
const PLAN_PATH = '/api/ide/plan-mode'

/** Resolves the workspace id for the panel's project directory. */
const CONTEXT_PATH = '/api/ide/context'

/** Live session feed (Server-Sent Events). */
const STREAM_PATH = '/api/ide/stream'

/** One-shot answer to a pending approval prompt. */
const APPROVAL_PATH = '/api/ide/approval'

/** Reads and changes the panel session's permission preset. */
const PERMISSION_PATH = '/api/ide/permission'

/** Keeps an idle EventSource (and its Chromium socket) out of proxy timeouts. */
const HEARTBEAT_MS = 15_000

/**
 * How long a pending approval survives its panel disconnecting. An IDE refresh
 * or a reconnecting EventSource must not turn a live prompt into a failure, but
 * a closed panel must not keep a tool blocked forever either.
 */
const PANEL_GRACE_MS = 10_000

/** Where this module lives, so the HTML is found next to it. */
const HERE = dirname(fileURLToPath(import.meta.url))

/**
 * Tools refused while Plan mode is on.
 *
 * A deny-list is used rather than an allow-list so ordinary read-only and
 * bookkeeping tools (read, glob, grep, todo, web) keep working without being
 * enumerated — a new read-only tool should not need a code change here.
 */
const WRITE_TOOLS = new Set([
  // Harness filesystem and shell
  'write',
  'edit',
  'ops_bash',
  'ops_replace',
  'pwsh',
  'apply_patch',
  'str_replace_editor',
  // IDE mutations and executions (MCP, prefixed by the tool bridge)
  'mcp__ide__apply_patch',
  'mcp__ide__create_new_file',
  'mcp__ide__rename_refactoring',
  'mcp__ide__reformat_file',
  'mcp__ide__execute_run_configuration',
  'mcp__ide__execute_terminal_command',
  'mcp__ide__build_project',
])

/** Sessions whose panel switched to Plan mode. Keyed by session id (`agent.id`). */
const planSessions = new Set()

function readPage() {
  return readFileSync(join(HERE, 'ui', 'index.html'), 'utf8')
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

/** The wire projection of one pending approval; keeps the settle closure private. */
function approvalView(entry) {
  return {
    id: entry.id,
    sessionId: entry.sessionId,
    toolName: entry.toolName,
    ...(entry.callId === undefined ? {} : { callId: entry.callId }),
    ...(entry.reason === undefined ? {} : { reason: entry.reason }),
    ...(entry.displayReason === undefined ? {} : { displayReason: entry.displayReason }),
    createdAt: entry.createdAt,
  }
}

/**
 * Owns the panel connections and the approval prompts waiting on them.
 *
 * Both halves are keyed by session because a panel shows exactly one session:
 * an approval is only routed to (and only answered from) a session with a live
 * subscriber, and a subscriber that goes away fails its prompts closed after a
 * short grace period.
 */
function createPanelHub() {
  /** session id -> live SSE writers. */
  const subscribers = new Map()
  /** approval request id -> pending prompt (with its settling closure). */
  const pending = new Map()
  /** session id -> timer that fails pending prompts after a disconnect. */
  const graces = new Map()

  const broadcast = (sessionId, event, data) => {
    const writers = subscribers.get(sessionId)
    if (writers === undefined) return
    for (const write of writers) write(event, data)
  }

  const failSession = (sessionId) => {
    for (const entry of [...pending.values()]) {
      if (entry.sessionId === sessionId) entry.settle('unavailable')
    }
  }

  const cancelGrace = (sessionId) => {
    const timer = graces.get(sessionId)
    if (timer === undefined) return
    clearTimeout(timer)
    graces.delete(sessionId)
  }

  return {
    /** Whether a panel is currently watching this session. */
    hasSubscriber(sessionId) {
      const writers = subscribers.get(sessionId)
      return writers !== undefined && writers.size > 0
    },

    /** Register one SSE writer; returns its disposer. */
    subscribe(sessionId, write) {
      let writers = subscribers.get(sessionId)
      if (writers === undefined) {
        writers = new Set()
        subscribers.set(sessionId, writers)
      }
      writers.add(write)
      // A panel reconnecting inside the grace window keeps its pending prompts.
      cancelGrace(sessionId)
      return () => {
        const live = subscribers.get(sessionId)
        if (live !== undefined) {
          live.delete(write)
          if (live.size === 0) subscribers.delete(sessionId)
        }
        if (subscribers.has(sessionId)) return
        const timer = setTimeout(() => {
          graces.delete(sessionId)
          failSession(sessionId)
        }, PANEL_GRACE_MS)
        timer.unref?.()
        graces.set(sessionId, timer)
      }
    },

    /** Prompts that arrived before this subscriber connected. */
    pendingFor(sessionId) {
      return [...pending.values()]
        .filter((entry) => entry.sessionId === sessionId)
        .map(approvalView)
    },

    /**
     * Ask the panel and wait for its decision.
     *
     * @param sessionId - session whose panel should answer.
     * @param request - Host approval request (tool identity, reason, signal).
     * @returns the closed approval outcome; the signal settles `cancelled`.
     */
    ask(sessionId, request) {
      const id = randomUUID()
      return new Promise((resolve) => {
        const signal = request.signal
        let settled = false
        const settle = (outcome) => {
          if (settled) return
          settled = true
          pending.delete(id)
          if (signal !== undefined) signal.removeEventListener('abort', onAbort)
          resolve(outcome)
          broadcast(sessionId, 'approval-resolved', { id, outcome })
        }
        // Resolves the approval seam's race with the turn's abort signal; a
        // late decision from the panel is then simply unknown and dropped.
        const onAbort = () => settle('cancelled')
        const entry = {
          id,
          sessionId,
          toolName: typeof request.toolName === 'string' ? request.toolName : 'tool',
          callId: request.callId,
          reason: request.reason,
          displayReason: request.displayReason,
          createdAt: Date.now(),
          settle,
        }
        pending.set(id, entry)
        console.log('[dsh-ide-ui] approval asked', sessionId, entry.toolName, id)
        if (signal !== undefined) {
          if (signal.aborted) {
            onAbort()
            return
          }
          signal.addEventListener('abort', onAbort, { once: true })
        }
        broadcast(sessionId, 'approval', approvalView(entry))
      })
    },

    /** Resolve one prompt from the panel. Unknown ids mean it already settled. */
    answer(id, outcome) {
      const entry = pending.get(id)
      if (entry === undefined) return undefined
      entry.settle(outcome)
      return outcome
    },

    /** Plugin unload: no panel survives to answer, so fail everything closed. */
    dispose() {
      for (const sessionId of [...subscribers.keys()]) subscribers.delete(sessionId)
      for (const timer of graces.values()) clearTimeout(timer)
      graces.clear()
      for (const entry of [...pending.values()]) entry.settle('unavailable')
    },
  }
}

export function apply(ctx) {
  // Enforce at the tool boundary: a denial here cannot be overridden later in
  // the pipeline, and the model sees the reason as the tool's failure.
  ctx.inject(['tools'], (toolsCtx) => {
    toolsCtx.effect(() => {
      const dispose = toolsCtx.tools.guard((execution) => {
        // Only the session whose panel is in Plan mode is restricted; other
        // sessions (and subagents without an agent) keep full access.
        const sessionId = execution.agent?.id
        if (sessionId === undefined || !planSessions.has(sessionId)) {
          return undefined
        }
        if (!WRITE_TOOLS.has(execution.name)) {
          return undefined
        }
        return '计划模式已开启：当前只允许只读操作。请先切回 Act 模式再执行写操作或命令。'
      })
      console.log('[dsh-ide-ui] plan-mode guard registered')
      return () => dispose()
    }, 'dsh-ide-ui: plan mode guard')
  })

  const hub = createPanelHub()

  // Prepend: the approval seam runs listeners outermost-first, and the Web UI's
  // own answerer forwards every request to a connected browser. A panel-owned
  // session must be decided where its user is looking — the IDE — so this
  // listener claims it before that forwarding happens. Everything else falls
  // through with `next()`, keeping the desktop prompt for desktop sessions.
  ctx.on('approval/request', (request, next) => {
    const sessionId = request?.agent?.session?.id
    if (typeof sessionId !== 'string' || !hub.hasSubscriber(sessionId)) return next()
    return hub.ask(sessionId, request)
  }, { prepend: true })

  ctx.effect(() => () => {
    hub.dispose()
    console.log('[dsh-ide-ui] panel hub disposed')
  }, 'dsh-ide-ui: panel approvals')

  ctx.inject(['connection', 'workspaceRegistry', 'sessions'], (connectionCtx) => {
    const registry = connectionCtx.workspaceRegistry
    const sessions = connectionCtx.sessions

    const servePage = async () => new Response(readPage(), {
      headers: {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-store',
      },
    })

    /**
     * Resolves the workspace for a directory. `create` is idempotent — it
     * returns the existing record for a known path — so the panel can call it on
     * every boot and always get a stable id to create the session under.
     */
    const serveContext = async (request) => {
      const cwd = new URL(request.url).searchParams.get('cwd') ?? ''
      if (cwd.length === 0) {
        return json({ error: 'cwd required' }, 400)
      }
      try {
        const project = await registry.create(cwd)
        return json({ workspaceId: project?.id ?? null, path: project?.path ?? cwd })
      } catch (error) {
        console.error('[dsh-ide-ui] workspace resolve failed', error)
        return json({ error: String(error?.message ?? error) }, 500)
      }
    }

    const servePlan = async (request) => {
      let body
      try {
        body = await request.json()
      } catch {
        return json({ error: 'invalid body' }, 400)
      }
      const sessionId = typeof body?.sessionId === 'string' ? body.sessionId : null
      if (sessionId === null) {
        return json({ error: 'sessionId required' }, 400)
      }
      if (body.enabled === true) {
        planSessions.add(sessionId)
      } else {
        planSessions.delete(sessionId)
      }
      console.log('[dsh-ide-ui] plan mode', sessionId, '=', planSessions.has(sessionId))
      return json({ sessionId, planMode: planSessions.has(sessionId) })
    }

    /**
     * One SSE response per open panel. The opening payload is the whole current
     * log (so a reconnect after a desktop restart rebuilds the transcript), then
     * durable records and live assistant frames follow until the socket closes.
     */
    const serveStream = (request) => {
      const sessionId = new URL(request.url).searchParams.get('sessionId') ?? ''
      const session = sessionId === '' ? undefined : sessions.get(sessionId)
      if (session === undefined) {
        return json({ error: 'unknown session', sessionId }, 404)
      }

      const encoder = new TextEncoder()
      let cleanup = () => {}
      const stream = new ReadableStream({
        start(controller) {
          let closed = false
          let heartbeat
          const disposers = []

          const write = (chunk) => {
            if (closed) return
            try {
              controller.enqueue(encoder.encode(chunk))
            } catch {
              // The peer vanished between the abort signal and this write.
              closed = true
            }
          }
          const send = (event, data) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)

          const stop = () => {
            if (closed) return
            closed = true
            clearInterval(heartbeat)
            for (const dispose of disposers) {
              try {
                dispose()
              } catch {
                // A disposer that already ran is the desired state.
              }
            }
            request.signal.removeEventListener('abort', stop)
            try {
              controller.close()
            } catch {
              // Closing twice is only a race with the transport teardown.
            }
          }
          cleanup = stop

          disposers.push(hub.subscribe(sessionId, send))
          disposers.push(ctx.on('session/event', (target, event) => {
            if (target.id !== sessionId) return
            send('event', event)
          }, { global: true }))
          disposers.push(ctx.on('agent/assistant-stream', ({ agent, frame }) => {
            if (agent?.session?.id !== sessionId) return
            send('assistant', frame)
          }, { global: true }))

          request.signal.addEventListener('abort', stop, { once: true })
          heartbeat = setInterval(() => write(': ping\n\n'), HEARTBEAT_MS)
          heartbeat.unref?.()

          let events = []
          try {
            events = session.snapshotEvents()
          } catch (error) {
            // The session was disposed between the lookup and this read; an
            // empty baseline still lets the live listeners take over.
            console.warn('[dsh-ide-ui] snapshot read failed', error)
          }
          send('snapshot', {
            sessionId,
            cursor: events.at(-1)?.seq ?? -1,
            events,
          })
          // A prompt may already be waiting (panel reloaded mid-turn).
          for (const view of hub.pendingFor(sessionId)) send('approval', view)

          if (request.signal.aborted) stop()
        },
        cancel() {
          cleanup()
        },
      })

      return new Response(stream, {
        headers: {
          'content-type': 'text/event-stream; charset=utf-8',
          'cache-control': 'no-store, no-transform',
          'x-accel-buffering': 'no',
        },
      })
    }

    /** The panel's one-shot approval decision. */
    const serveDecision = async (request) => {
      let body
      try {
        body = await request.json()
      } catch {
        return json({ error: 'invalid body' }, 400)
      }
      const requestId = typeof body?.requestId === 'string' ? body.requestId : ''
      const decision = body?.decision
      if (requestId === '' || (decision !== 'allow' && decision !== 'reject')) {
        return json({ error: 'requestId and decision required' }, 400)
      }
      const outcome = hub.answer(requestId, decision === 'allow' ? 'allowed-once' : 'rejected')
      if (outcome === undefined) {
        return json({ error: 'no pending approval' }, 404)
      }
      console.log('[dsh-ide-ui] approval answered', requestId, '=', outcome)
      return json({ ok: true, outcome })
    }

    /**
     * The panel's permission picker. Reading returns the preset catalog plus the
     * session's effective preset; writing switches it through the same service
     * the desktop UI uses, which appends the three durable knob events.
     */
    const servePermission = async (request) => {
      // Optional capability: a composition without the preset service keeps the
      // panel working, it just hides the picker (the route answers 501).
      const permissionPresets = connectionCtx.get('permissionPresets')
      if (permissionPresets === undefined) {
        return json({ error: 'permission presets are not composed in this profile' }, 501)
      }
      const url = new URL(request.url)
      let sessionId = ''
      let preset = ''
      if (request.method === 'GET') {
        sessionId = url.searchParams.get('sessionId') ?? ''
      } else {
        let body
        try {
          body = await request.json()
        } catch {
          return json({ error: 'invalid body' }, 400)
        }
        sessionId = typeof body?.sessionId === 'string' ? body.sessionId : ''
        preset = typeof body?.preset === 'string' ? body.preset : ''
      }
      const session = sessionId === '' ? undefined : sessions.get(sessionId)
      if (session === undefined) {
        return json({ error: 'unknown session', sessionId }, 404)
      }
      const catalog = permissionPresets.catalog()
      if (request.method === 'GET') {
        return json({
          current: permissionPresets.current(session),
          options: catalog.options,
          defaultPreset: catalog.defaultPreset,
        })
      }
      if (preset === '') {
        return json({ error: 'preset required' }, 400)
      }
      try {
        permissionPresets.set(session, preset)
      } catch (error) {
        return json({ error: String(error?.message ?? error) }, 400)
      }
      const option = catalog.options.find((item) => item.value === preset)
      console.log('[dsh-ide-ui] permission preset', sessionId, '=', preset)
      return json({ ok: true, current: permissionPresets.current(session), name: option?.name ?? preset })
    }

    try {
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: PAGE_PATH,
        methods: ['GET'],
        requestBody: 'buffered',
        fetch: servePage,
      })
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: PLAN_PATH,
        methods: ['POST'],
        requestBody: 'buffered',
        fetch: servePlan,
      })
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: CONTEXT_PATH,
        methods: ['GET'],
        requestBody: 'buffered',
        fetch: serveContext,
      })
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: STREAM_PATH,
        methods: ['GET'],
        requestBody: 'buffered',
        fetch: serveStream,
      })
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: APPROVAL_PATH,
        methods: ['POST'],
        requestBody: 'buffered',
        fetch: serveDecision,
      })
      connectionCtx.connection.registerFetchRoute(connectionCtx, {
        path: PERMISSION_PATH,
        methods: ['GET', 'POST'],
        requestBody: 'buffered',
        fetch: servePermission,
      })
      console.log('[dsh-ide-ui] routes registered:', PAGE_PATH, PLAN_PATH, CONTEXT_PATH, STREAM_PATH, APPROVAL_PATH, PERMISSION_PATH)
    } catch (error) {
      console.error('[dsh-ide-ui] route registration failed', error)
    }
  })
}
