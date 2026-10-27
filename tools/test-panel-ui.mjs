/**
 * Panel UI smoke test: runs `ui/index.html`'s module script against a tiny DOM
 * stub and a mocked fetch, then drives the SSE frames the DSH plugin would send.
 *
 * This catches the failure modes a syntax check cannot: a missing element id, a
 * render path that throws on a real event, or an approval click that never
 * reaches the POST route.
 *
 * Run: node tools/test-panel-ui.mjs
 */

import assert from 'node:assert/strict'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const html = readFileSync(join(root, 'dsh-plugin', 'ui', 'index.html'), 'utf8')
const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script, 'panel script not found')

/* ------------------------------------------------------------------ *
 * DOM stub
 * ------------------------------------------------------------------ */

class ClassList {
  constructor(node) {
    this.node = node
    this.set = new Set()
  }
  add(...names) { for (const name of names) this.set.add(name) }
  remove(...names) { for (const name of names) this.set.delete(name) }
  contains(name) { return this.set.has(name) }
  toggle(name, force) {
    const want = force === undefined ? !this.set.has(name) : force
    if (want) this.set.add(name)
    else this.set.delete(name)
    return want
  }
}

class FakeNode {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase()
    this.children = []
    this.parent = null
    this.attributes = {}
    this.dataset = {}
    this.style = {}
    this.listeners = new Map()
    this.classList = new ClassList(this)
    this._className = ''
    this._text = ''
    this._html = ''
    this.hidden = false
    this.disabled = false
    this.open = false
    this.value = ''
    this.scrollTop = 0
    this.scrollHeight = 0
    this.clientHeight = 0
    this.id = ''
  }
  get className() { return this._className }
  set className(value) {
    this._className = String(value)
    this.classList.set = new Set(this._className.split(/\s+/).filter(Boolean))
  }
  get textContent() { return this._text + this._html + this.children.map((child) => child.textContent).join('') }
  set textContent(value) {
    this._text = String(value)
    this.children = []
  }
  get innerHTML() { return this._html }
  set innerHTML(value) {
    this._html = String(value)
    this.children = []
  }
  appendChild(child) {
    if (child.parent !== null) child.parent.removeChild(child)
    child.parent = this
    this.children.push(child)
    return child
  }
  append(...nodes) {
    for (const node of nodes) {
      if (typeof node === 'string') {
        const text = new FakeNode('#text')
        text.textContent = node
        this.appendChild(text)
      } else {
        this.appendChild(node)
      }
    }
  }
  removeChild(child) {
    const index = this.children.indexOf(child)
    if (index >= 0) {
      this.children.splice(index, 1)
      child.parent = null
    }
    return child
  }
  remove() {
    this.parent?.removeChild(this)
  }
  addEventListener(type, listener) {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
  }
  removeEventListener(type, listener) {
    const list = this.listeners.get(type) ?? []
    const index = list.indexOf(listener)
    if (index >= 0) list.splice(index, 1)
  }
  focus() {
    fakeDocument.activeElement = this
  }
  contains(other) {
    if (other === this) return true
    return this.children.some((child) => child.contains(other))
  }
  querySelectorAll(selector) {
    const out = []
    const match = (node) => {
      if (selector === 'button') return node.tagName === 'BUTTON'
      if (selector === 'button.active') return node.tagName === 'BUTTON' && node.classList.contains('active')
      if (selector === 'button[data-value]') return node.tagName === 'BUTTON' && 'value' in node.dataset
      return false
    }
    const walk = (node) => {
      for (const child of node.children) {
        if (match(child)) out.push(child)
        walk(child)
      }
    }
    walk(this)
    return out
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] ?? null
  }
  closest(selector) {
    let node = this
    while (node !== null) {
      if (selector === 'button' && node.tagName === 'BUTTON') return node
      if (selector === 'button[data-value]' && node.tagName === 'BUTTON' && 'value' in node.dataset) return node
      node = node.parent
    }
    return null
  }
}

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1])
const nodes = new Map()
for (const id of ids) {
  const node = new FakeNode(id.startsWith('appr') ? 'button' : id === 'mode' || id.endsWith('Menu') ? 'div' : 'div')
  node.id = id
  nodes.set(id, node)
}
// `#mode` holds the Act/Plan buttons, and the effort menu holds its options.
for (const mode of ['act', 'plan']) {
  const button = new FakeNode('button')
  button.dataset.mode = mode
  button.className = mode === 'act' ? 'active' : ''
  nodes.get('mode').appendChild(button)
}
for (const effort of ['low', 'medium', 'high', 'max']) {
  const button = new FakeNode('button')
  button.dataset.value = effort
  nodes.get('effortMenu').appendChild(button)
}

const fakeDocument = {
  activeElement: null,
  documentElement: new FakeNode('html'),
  listeners: new Map(),
  getElementById(id) {
    return nodes.get(id) ?? null
  },
  createElement(tag) {
    return new FakeNode(tag)
  },
  addEventListener(type, listener) {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
  },
  dispatch(type, event) {
    for (const listener of [...(this.listeners.get(type) ?? [])]) listener(event)
  },
}

/* ------------------------------------------------------------------ *
 * Browser globals
 * ------------------------------------------------------------------ */

const approvals = []
const requests = []
let streamController
const encoder = new TextEncoder()

const jsonResponse = (body) => new Response(JSON.stringify(body), {
  status: 200,
  headers: { 'content-type': 'application/json' },
})

const fetchMock = async (url, options = {}) => {
  const address = String(url)
  requests.push({ url: address, method: options.method ?? 'GET', body: options.body })
  if (address.startsWith('/api/ide/context')) return jsonResponse({ workspaceId: 'workspace-1', path: 'C:/work' })
  if (address.startsWith('/api/session/create')) {
    return jsonResponse({ result: { ok: true, value: { sessionId: 'session-test' } } })
  }
  if (address.startsWith('/api/session/page')) {
    return jsonResponse({
      result: {
        ok: true,
        value: {
          records: [{
            type: 'event',
            event: {
              type: 'user/message',
              seq: 0,
              time: 1,
              data: { role: 'user', content: [{ type: 'text', text: '你好' }], source: { kind: 'user' } },
            },
          }],
        },
      },
    })
  }
  if (address.startsWith('/api/session/modelCatalog')) {
    return jsonResponse({
      result: { ok: true, value: { groups: [], default: { provider: 'p', model: 'm', reasoningEffort: 'max' } } },
    })
  }
  if (address.startsWith('/api/session/selectModel')) {
    return jsonResponse({ result: { ok: true, value: {} } })
  }
  if (address.startsWith('/api/ide/stream')) {
    const body = new ReadableStream({
      start(controller) {
        streamController = controller
      },
    })
    return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } })
  }
  if (address.startsWith('/api/ide/permission')) {
    if ((options.method ?? 'GET') === 'POST') {
      return jsonResponse({ ok: true, current: JSON.parse(options.body).preset, name: '只读' })
    }
    return jsonResponse({
      current: 'danger-full-access',
      options: [
        { value: 'read-only', name: '只读', description: '只能看' },
        { value: 'danger-full-access', name: '完全权限', description: '全盘可写' },
      ],
      defaultPreset: 'danger-full-access',
    })
  }
  if (address.startsWith('/api/ide/approval')) {
    approvals.push(JSON.parse(options.body))
    return jsonResponse({ ok: true, outcome: 'allowed-once' })
  }
  if (address.startsWith('/api/ide/plan-mode')) {
    return jsonResponse({
      sessionId: 'session-test',
      planMode: JSON.parse(options.body).enabled,
    })
  }
  return jsonResponse({ result: { ok: true, value: {} } })
}

const storage = new Map()
globalThis.document = fakeDocument
globalThis.window = {
  matchMedia: () => ({ matches: false }),
  addEventListener() {},
}
globalThis.location = { search: '?cwd=C%3A%5Cwork' }
globalThis.fetch = fetchMock
globalThis.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
}
globalThis.requestAnimationFrame = (callback) => setTimeout(callback, 0)
globalThis.cancelAnimationFrame = (id) => clearTimeout(id)
globalThis.EventSource = undefined

/* ------------------------------------------------------------------ *
 * Run the panel script
 * ------------------------------------------------------------------ */

const scriptPath = join(root, '.cache', 'panel-script-under-test.mjs')
mkdirSync(join(root, '.cache'), { recursive: true })
writeFileSync(scriptPath, script)
await import('file:///' + scriptPath.replace(/\\/g, '/'))

const tick = (ms = 20) => new Promise((resolve) => setTimeout(resolve, ms))
await tick(80)

const thread = fakeDocument.getElementById('thread')
const approvalCard = fakeDocument.getElementById('approval')

// Boot: initial page came from session/page.
assert.ok(thread.children.length > 0, 'thread rendered the history snapshot')
assert.equal(fakeDocument.getElementById('projectName').textContent, 'work')
assert.ok(requests.some((request) => request.url.startsWith('/api/ide/stream')), 'panel opened the SSE stream')
assert.equal(fakeDocument.getElementById('permBtn').textContent, '权限：完全权限', 'permission picker shows the current preset')
assert.equal(fakeDocument.getElementById('permMenu').children.length, 2, 'permission picker filled from the catalog')

const push = (name, data) => {
  streamController.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`))
}

// Live attempt: start + two text deltas render a growing assistant block.
push('assistant', { type: 'start', attemptId: 'a1', revision: 1, turn: 1, step: 1 })
await tick()
push('assistant', { type: 'chunk', attemptId: 'a1', revision: 2, index: 0, time: 1, chunk: { type: 'reasoning-delta', index: 0, text: '先想一下' } })
await tick()
push('assistant', { type: 'chunk', attemptId: 'a1', revision: 3, index: 1, time: 2, chunk: { type: 'text-delta', index: 0, text: '正在' } })
push('assistant', { type: 'chunk', attemptId: 'a1', revision: 4, index: 2, time: 3, chunk: { type: 'text-delta', index: 0, text: '输出' } })
await tick(40)
const live = thread.children.find((node) => node.classList.contains('live'))
assert.ok(live, 'streaming produced a live assistant block')
assert.ok(live.textContent.includes('正在输出'), 'live block shows accumulated deltas: ' + live.textContent)
assert.ok(live.textContent.includes('先想一下'), 'live block shows reasoning')

// A durable user message arrives: still rendered once, deduped by seq.
push('event', {
  type: 'user/message',
  seq: 1,
  time: 4,
  data: { role: 'user', content: [{ type: 'text', text: '第二条' }], source: { kind: 'user' } },
})
await tick()
assert.ok(thread.textContent.includes('第二条'), 'durable event appended to the transcript')

// Mode switch: Plan → Act must actively tell the model to carry on.
push('event', {
  type: 'assistant/message',
  seq: 2,
  time: 5,
  data: { message: { content: [{ type: 'text', text: '# 计划\n1. 先做这个' }] } },
})
await tick()

const modeNode = fakeDocument.getElementById('mode')
const planButton = modeNode.children.find((node) => node.dataset.mode === 'plan')
const actButton = modeNode.children.find((node) => node.dataset.mode === 'act')
const clickMode = (node) => {
  for (const listener of modeNode.listeners.get('click') ?? []) listener({ target: node })
}
const promptRequests = () => requests.filter((request) => request.url.startsWith('/api/session/prompt'))

clickMode(planButton)
await tick(40)
assert.equal(fakeDocument.getElementById('modeHint').textContent, '只读：写操作会被拒绝', 'Plan hint')

const beforeAct = promptRequests().length
clickMode(actButton)
await tick(60)
assert.equal(fakeDocument.getElementById('modeHint').textContent, '直接执行改动', 'Act hint')
const afterAct = promptRequests()
assert.equal(afterAct.length, beforeAct + 1, 'switching to Act sends exactly one continuation prompt')
assert.ok(afterAct.at(-1).body.includes('模式已切换'), 'continuation prompt names the mode switch')
assert.ok(afterAct.at(-1).body.includes('执行'), 'continuation prompt asks the model to execute')

const beforePlanAgain = promptRequests().length
clickMode(planButton)
await tick(40)
assert.equal(promptRequests().length, beforePlanAgain, 'switching to Plan does not auto-send')
await tick(20)

// The Plan read-only prefix is model input only; it must not clutter the bubble.
const planPrefix = '[只读模式] 本轮只允许读取和分析，不要修改文件或执行写操作。\n\n'
push('event', {
  type: 'user/message',
  seq: 3,
  time: 6,
  data: { role: 'user', content: [{ type: 'text', text: planPrefix + '把按钮颜色改一下' }], source: { kind: 'user' } },
})
await tick()
assert.ok(thread.textContent.includes('把按钮颜色改一下'), 'plan message text is shown')
assert.ok(!thread.textContent.includes('只读模式'), 'plan prefix is hidden from the transcript')

// Approval: the card becomes visible with the tool name.
push('approval', {
  id: 'appr-1',
  sessionId: 'session-test',
  toolName: 'ops_bash',
  callId: 'call-1',
  reason: '需要提升权限',
})
await tick()
assert.equal(approvalCard.hidden, false, 'approval card shown')
assert.ok(fakeDocument.getElementById('apprTool').textContent.includes('ops_bash'))
assert.ok(fakeDocument.getElementById('apprReason').textContent.includes('需要提升权限'))

// Clicking allow posts the decision and hides the card.
const allow = fakeDocument.getElementById('apprAllow')
for (const listener of allow.listeners.get('click') ?? []) listener({})
await tick(60)
assert.deepEqual(approvals[0], { requestId: 'appr-1', decision: 'allow' })
assert.equal(approvalCard.hidden, true, 'approval card hidden after decision')

// A resolved frame also retires the card (e.g. the turn was cancelled elsewhere).
push('approval', { id: 'appr-2', sessionId: 'session-test', toolName: 'write' })
await tick()
assert.equal(approvalCard.hidden, false)
push('approval-resolved', { id: 'appr-2', outcome: 'cancelled' })
await tick()
assert.equal(approvalCard.hidden, true, 'approval-resolved retires the card')

console.log('ok: panel boot, streaming render, durable events, approval card, decision POST')
process.exit(0)
