// 生成 README 截图：先启动后端(8080)，再用带远程调试端口的 Chrome 打开页面
//   chrome --headless=new --remote-debugging-port=9222 --user-data-dir=%TEMP%\acd-chrome about:blank
//   node scripts/ui-screenshots.mjs screenshots
import { writeFileSync, mkdirSync } from 'node:fs'

const BASE = 'http://127.0.0.1:8080'
const OUT = process.argv[2] || 'screenshots'
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let nonce = 0

class Cdp {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl)
    this.seq = 0
    this.pending = new Map()
    this.errors = []
  }
  async open() {
    await new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true })
      this.ws.addEventListener('error', reject, { once: true })
    })
    this.ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && this.pending.has(msg.id)) {
        const item = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) item.reject(new Error(JSON.stringify(msg.error)))
        else item.resolve(msg.result)
        return
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const detail = msg.params.exceptionDetails
        this.errors.push((detail.exception && detail.exception.description) || JSON.stringify(detail))
      }
    })
    return this
  }
  send(method, params = {}) {
    const id = ++this.seq
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result ? result.result.value : undefined
  }
  async goto(hash, waitFor) {
    nonce += 1
    await this.send('Page.navigate', { url: BASE + '/?v=' + nonce + hash })
    for (let i = 0; i < 100; i += 1) {
      await sleep(150)
      const state = await this.evaluate('document.readyState').catch(() => null)
      if (state !== 'complete') continue
      if (!waitFor) break
      const found = await this.evaluate('!!document.querySelector(' + JSON.stringify(waitFor) + ')').catch(() => false)
      if (found) break
    }
    await sleep(900)
  }
  async hover(selector, text) {
    const box = await this.evaluate([
      '(() => {',
      '  const nodes = [...document.querySelectorAll(' + JSON.stringify(selector) + ')];',
      '  const node = ' + (text ? 'nodes.find((n) => n.textContent.indexOf(' + JSON.stringify(text) + ') >= 0)' : 'nodes[0]') + ';',
      '  if (!node) return null;',
      '  const r = node.getBoundingClientRect();',
      '  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };',
      '})()',
    ].join('\n'))
    if (!box) return false
    await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y, button: 'none', buttons: 0 })
    await sleep(500)
    return true
  }
  async click(selector) {
    await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 0, y: 0, button: 'left', clickCount: 1 })
    return this.evaluate('document.querySelector(' + JSON.stringify(selector) + ').click()')
  }
  async shot(name) {
    const result = await this.send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(OUT + '/' + name + '.png', Buffer.from(result.data, 'base64'))
    console.log('shot ' + name)
  }
}

const loginExpr = [
  '(async () => {',
  '  const res = await fetch("/api/auth/login", {',
  '    method: "POST",',
  '    headers: { "Content-Type": "application/json" },',
  '    body: JSON.stringify({ username: "admin", password: "admin123" }),',
  '  });',
  '  const body = await res.json();',
  '  localStorage.setItem("acd_token", body.data.token);',
  '  localStorage.setItem("acd_user", JSON.stringify(body.data.user));',
  '  return body.code;',
  '})()',
].join('\n')

const target = await (await fetch('http://127.0.0.1:9222/json/new?about:blank', { method: 'PUT' })).json()
const cdp = await new Cdp(target.webSocketDebuggerUrl).open()
await cdp.send('Page.enable')
await cdp.send('Runtime.enable')
await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

await cdp.goto('/#/login', '.login-card')
await cdp.evaluate('localStorage.clear()')
await cdp.goto('/#/login', '.login-card')
await cdp.shot('01-login')

console.log('login:', await cdp.evaluate(loginExpr))
await cdp.goto('/#/', '.file-card')
await cdp.hover('.file-card', '使用说明')
await cdp.shot('02-drive-grid')

await cdp.evaluate('document.querySelector("input[value=list]").click()')
await sleep(1300)
await cdp.shot('03-drive-list')
await cdp.evaluate('document.querySelector("input[value=grid]").click()')
await sleep(1000)

const openTextPreview = [
  '(() => {',
  '  const card = [...document.querySelectorAll(".file-card")].find((c) => c.textContent.indexOf("使用说明") >= 0);',
  '  if (!card) return "no-card";',
  '  const btn = [...card.querySelectorAll(".el-button")].find((b) => b.textContent.indexOf("预览") >= 0);',
  '  if (!btn) return "no-button";',
  '  btn.click();',
  '  return "ok";',
  '})()',
].join('\n')
console.log('text preview:', await cdp.evaluate(openTextPreview))
await sleep(1800)
await cdp.shot('04-text-preview')
await cdp.evaluate('document.querySelector(".el-dialog__headerbtn").click()')
await sleep(700)

const openImages = [
  '(() => {',
  '  const card = [...document.querySelectorAll(".file-card")].find((c) => c.textContent.indexOf("图片") >= 0);',
  '  if (!card) return "no-folder";',
  '  card.dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));',
  '  return "ok";',
  '})()',
].join('\n')
console.log('open images folder:', await cdp.evaluate(openImages))
await sleep(1400)
await cdp.hover('.file-card', 'cover')
await cdp.shot('05-folder-images')

const openImagePreview = [
  '(() => {',
  '  const card = [...document.querySelectorAll(".file-card")].find((c) => c.textContent.indexOf("cover") >= 0);',
  '  if (!card) return "no-card";',
  '  const btn = [...card.querySelectorAll(".el-button")].find((b) => b.textContent.indexOf("预览") >= 0);',
  '  if (!btn) return "no-button";',
  '  btn.click();',
  '  return "ok";',
  '})()',
].join('\n')
console.log('image preview:', await cdp.evaluate(openImagePreview))
await sleep(2000)
await cdp.shot('06-image-preview')
await cdp.evaluate('document.querySelector(".el-dialog__headerbtn").click()')
await sleep(600)

await cdp.goto('/#/recycle', '.el-table__row')
await cdp.shot('07-recycle')

await cdp.goto('/#/admin', '.stat-card')
await cdp.shot('08-admin')

await cdp.evaluate('localStorage.setItem("acd_theme","dark")')
await cdp.goto('/#/', '.file-card')
await cdp.hover('.file-card', '使用说明')
await cdp.shot('09-drive-dark')

console.log('runtime errors:', JSON.stringify(cdp.errors.slice(0, 5)))
process.exit(0)
