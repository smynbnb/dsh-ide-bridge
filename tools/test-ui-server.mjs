/**
 * Offline checks for the IDE panel's streaming and approval plumbing.
 *
 * The DSH plugin API is a Cordis context: this harness provides just the
 * services `ui-server.js` touches (tools guard, connection fetch routes,
 * sessions, workspace registry) so the HTTP shapes and the approval answerer
 * can be exercised without restarting the desktop build.
 *
 * Run: node tools/test-ui-server.mjs
 */

import assert from 'node:assert/strict'
import { apply } from '../dsh-plugin/ui-server.js'

/* ------------------------------------------------------------------ *
 * Fake Cordis context
 * ------------------------------------------------------------------ */

function createHarness() {
  const listeners = new Map()
  const routes = new Map()
  const guards = []
  const effects = []

  let currentPreset = 'danger-full-access'
  const presetNames = new Set(['read-only', 'workspace-write', 'danger-full-access'])

  const events = {
    on(name, listener) {
      const list = listeners.get(name) ?? []
      list.push(listener)
      listeners.set(name, list)
      return () => {
        const current = listeners.get(name) ?? []
        const index = current.indexOf(listener)
        if (index >= 0) current.splice(index, 1)
      }
    },
    emit(name, ...args) {
      for (const listener of [...(listeners.get(name) ?? [])]) listener(...args)
    },
    waterfall(name, args, finalNext = async () => 'unavailable') {
      const chain = [...(listeners.get(name) ?? [])]
      const call = (index) => {
        const listener = chain[index]
        if (listener === undefined) return finalNext()
        return listener(...args, () => call(index + 1))
      }
      return call(0)
    },
  }

  const services = {
    tools: {
      guard(handler) {
        guards.push(handler)
        return () => {}
      },
    },
    connection: {
      registerFetchRoute(owner, route) {
        if (routes.has(route.path)) throw new Error('duplicate route ' + route.path)
        routes.set(route.path, route)
        return () => routes.delete(route.path)
      },
    },
    workspaceRegistry: {
      async create(cwd) {
        return { id: 'workspace-1', path: cwd }
      },
    },
    sessions: {
      get(id) {
        return id === SESSION_ID ? session : undefined
      },
    },
    permissionPresets: {
      catalog() {
        return {
          options: [
            { value: 'read-only', name: '只读' },
            { value: 'workspace-write', name: '工作区可写' },
            { value: 'danger-full-access', name: '完全权限' },
          ],
          defaultPreset: 'danger-full-access',
        }
      },
      current() {
        return currentPreset
      },
      set(_session, name) {
        if (!presetNames.has(name)) throw new Error('unknown preset ' + name)
        currentPreset = name
      },
    },
  }

  const sessionEvents = []
  const session = {
    id: SESSION_ID,
    seq: 0,
    snapshotEvents() {
      return sessionEvents.slice()
    },
  }

  const ctx = {
    inject(names, callback) {
      callback(ctx)
    },
    effect(callback) {
      const disposer = callback()
      if (typeof disposer === 'function') effects.push(disposer)
      return () => {}
    },
    on(name, listener) {
      return events.on(name, listener)
    },
    get(name) {
      return services[name]
    },
  }
  // Cordis exposes injected services as properties of the injected context.
  for (const name of Object.keys(services)) {
    Object.defineProperty(ctx, name, { get: () => services[name], enumerable: true })
  }

  return {
    ctx,
    events,
    routes,
    guards,
    session,
    sessionEvents,
    dispose() {
      for (const effect of effects.reverse()) effect()
    },
  }
}

const SESSION_ID = 'session-test'

/* ------------------------------------------------------------------ *
 * SSE reader
 * ------------------------------------------------------------------ */

function attachSse(response) {
  const decoder = new TextDecoder()
  const frames = []
  const waiters = []
  let buffer = ''
  let ended = false

  const flush = () => {
    for (const waiter of [...waiters]) {
      const found = frames.slice(waiter.from).find((frame) => frame.name === waiter.name)
      if (found === undefined) continue
      waiters.splice(waiters.indexOf(waiter), 1)
      waiter.resolve(found)
    }
  }

  const reading = (async () => {
    const reader = response.body.getReader()
    try {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let boundary
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          let name = 'message'
          const data = []
          for (const line of block.split('\n')) {
            if (line.startsWith(':')) continue
            if (line.startsWith('event:')) name = line.slice(6).trim()
            else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
          }
          if (data.length === 0) continue
          frames.push({ name, data: JSON.parse(data.join('\n')) })
          flush()
        }
      }
    } catch (error) {
      console.error('SSE reader stopped:', error)
    } finally {
      ended = true
      for (const waiter of waiters.splice(0)) waiter.reject(new Error('stream ended'))
    }
  })()

  return {
    frames,
    get ended() { return ended },
    waitFor(name, from = 0, timeoutMs = 3000) {
      const found = frames.slice(from).find((frame) => frame.name === name)
      if (found !== undefined) return Promise.resolve(found)
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const index = waiters.findIndex((waiter) => waiter.resolve === resolve)
          if (index >= 0) waiters.splice(index, 1)
          reject(new Error(`timed out waiting for SSE event ${name}`))
        }, timeoutMs)
        waiters.push({
          name,
          from,
          resolve: (frame) => { clearTimeout(timer); resolve(frame) },
          reject: (error) => { clearTimeout(timer); reject(error) },
        })
      })
    },
    async close() {
      await reading
    },
  }
}

/* ------------------------------------------------------------------ *
 * Checks
 * ------------------------------------------------------------------ */

const harness = createHarness()
apply(harness.ctx)

assert.equal(harness.routes.size, 6, 'expected six IDE routes')
for (const path of ['/api/ide-panel', '/api/ide/plan-mode', '/api/ide/context', '/api/ide/stream', '/api/ide/approval', '/api/ide/permission']) {
  assert.ok(harness.routes.has(path), `missing route ${path}`)
}

// --- SSE: snapshot, durable event, assistant frame -------------------------

harness.sessionEvents.push(
  { type: 'turn/start', seq: 0, time: 1, data: { turn: 1 } },
  {
    type: 'user/message',
    seq: 1,
    time: 2,
    data: { role: 'user', content: [{ type: 'text', text: '你好' }], source: { kind: 'user' } },
  },
)
harness.session.seq = harness.sessionEvents.length

const abort = new AbortController()
const streamRequest = new Request('http://127.0.0.1/api/ide/stream?sessionId=' + SESSION_ID, {
  signal: abort.signal,
})
const streamResponse = harness.routes.get('/api/ide/stream').fetch(streamRequest)
const sse = attachSse(streamResponse)

const snapshot = await sse.waitFor('snapshot')
assert.equal(snapshot.data.sessionId, SESSION_ID)
assert.equal(snapshot.data.cursor, 1)
assert.equal(snapshot.data.events.length, 2, 'snapshot carries the whole log')

harness.events.emit('session/event', harness.session, {
  type: 'assistant/message',
  seq: 2,
  time: 3,
  data: { message: { content: [{ type: 'text', text: '你好！' }] } },
})
const durable = await sse.waitFor('event')
assert.equal(durable.data.seq, 2)

harness.events.emit('agent/assistant-stream', {
  agent: { session: { id: SESSION_ID } },
  frame: { type: 'chunk', attemptId: 'a1', revision: 2, index: 0, time: 4, chunk: { type: 'text-delta', index: 0, text: 'Hello' } },
})
const live = await sse.waitFor('assistant')
assert.equal(live.data.chunk.text, 'Hello')

// Unknown session is a 404 so the panel can fall back to polling.
const missing = await harness.routes.get('/api/ide/stream').fetch(
  new Request('http://127.0.0.1/api/ide/stream?sessionId=nope'),
)
assert.equal(missing.status, 404)

// --- Approval: routed to the panel, settled by the decision POST ----------

const approvalMark = sse.frames.length
const approvalPromise = harness.events.waterfall('approval/request', [{
  agent: { session: { id: SESSION_ID } },
  toolName: 'ops_bash',
  callId: 'call-1',
  reason: '需要提升权限',
}])
const approvalFrame = await sse.waitFor('approval', approvalMark)
assert.equal(approvalFrame.data.toolName, 'ops_bash')
assert.equal(approvalFrame.data.reason, '需要提升权限')
assert.equal(typeof approvalFrame.data.id, 'string')

const decision = await harness.routes.get('/api/ide/approval').fetch(new Request('http://127.0.0.1/api/ide/approval', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ requestId: approvalFrame.data.id, decision: 'allow' }),
}))
assert.equal(decision.status, 200)
assert.deepEqual(await decision.json(), { ok: true, outcome: 'allowed-once' })
assert.equal(await approvalPromise, 'allowed-once')
const resolved = await sse.waitFor('approval-resolved')
assert.equal(resolved.data.outcome, 'allowed-once')

// A second decision for the same id is stale.
const stale = await harness.routes.get('/api/ide/approval').fetch(new Request('http://127.0.0.1/api/ide/approval', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ requestId: approvalFrame.data.id, decision: 'reject' }),
}))
assert.equal(stale.status, 404)

// --- Approval: no panel subscriber delegates to the next answerer ----------

let delegated = false
const outcome = await harness.events.waterfall('approval/request', [{
  agent: { session: { id: 'someone-else' } },
  toolName: 'write',
}], () => {
  delegated = true
  return Promise.resolve('rejected')
})
assert.equal(outcome, 'rejected')
assert.equal(delegated, true, 'unknown session must fall through to the desktop answerer')

// --- Approval: abort signal settles cancelled ------------------------------

const turnAbort = new AbortController()
const cancelMark = sse.frames.length
const cancelledPromise = harness.events.waterfall('approval/request', [{
  agent: { session: { id: SESSION_ID } },
  toolName: 'pwsh',
  signal: turnAbort.signal,
}])
await sse.waitFor('approval', cancelMark)
turnAbort.abort()
assert.equal(await cancelledPromise, 'cancelled')
const cancelledResolved = await sse.waitFor('approval-resolved', cancelMark)
assert.equal(cancelledResolved.data.outcome, 'cancelled')

// --- Plan mode guard -------------------------------------------------------

const guard = harness.guards[0]
assert.equal(typeof guard, 'function')
assert.equal(guard({ name: 'write', agent: { id: SESSION_ID } }), undefined)
await harness.routes.get('/api/ide/plan-mode').fetch(new Request('http://127.0.0.1/api/ide/plan-mode', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ sessionId: SESSION_ID, enabled: true }),
}))
assert.match(guard({ name: 'write', agent: { id: SESSION_ID } }) ?? '', /计划模式/)
assert.equal(guard({ name: 'read', agent: { id: SESSION_ID } }), undefined)

// --- Permission preset read/write -----------------------------------------

const permissionGet = await harness.routes.get('/api/ide/permission').fetch(
  new Request('http://127.0.0.1/api/ide/permission?sessionId=' + SESSION_ID),
)
const permissionCatalog = await permissionGet.json()
assert.equal(permissionCatalog.current, 'danger-full-access')
assert.equal(permissionCatalog.options.length, 3)

const permissionSet = await harness.routes.get('/api/ide/permission').fetch(new Request('http://127.0.0.1/api/ide/permission', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ sessionId: SESSION_ID, preset: 'read-only' }),
}))
assert.equal(permissionSet.status, 200)
assert.equal((await permissionSet.json()).current, 'read-only')

const permissionBad = await harness.routes.get('/api/ide/permission').fetch(new Request('http://127.0.0.1/api/ide/permission', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ sessionId: SESSION_ID, preset: 'nope' }),
}))
assert.equal(permissionBad.status, 400)

// --- Disconnect cleanup ----------------------------------------------------

abort.abort()
await sse.close()

// Plugin unload fails any leftover prompts closed.
const pendingPromise = harness.events.waterfall('approval/request', [{
  agent: { session: { id: SESSION_ID } },
  toolName: 'edit',
}])
harness.dispose()
assert.equal(await pendingPromise, 'unavailable')

console.log('ok: routes, SSE, approval routing, delegation, cancel, plan guard, permission picker, dispose')
process.exit(0)
