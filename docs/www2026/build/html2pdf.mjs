// 用 Chrome DevTools Protocol 把 HTML 打成 PDF
// 用法: node html2pdf.mjs <file.html> <out.pdf>
import { writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'

const [, , inHtml, outPdf] = process.argv
if (!inHtml || !outPdf) { console.error('usage: node html2pdf.mjs <in.html> <out.pdf>'); process.exit(1) }
const htmlAbs = resolve(inHtml)
const pdfAbs = resolve(outPdf)
mkdirSync(dirname(pdfAbs), { recursive: true })

const CHROME = process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9333
const profile = resolve(dirname(pdfAbs), '.chrome-pdf-profile')

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--allow-file-access-from-files',
  '--remote-debugging-port=' + PORT,
  '--user-data-dir=' + profile,
  'about:blank',
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitDevtools() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch('http://127.0.0.1:' + PORT + '/json/version')
      if (res.ok) return
    } catch { /* retry */ }
    await sleep(300)
  }
  throw new Error('chrome devtools not ready')
}

class Cdp {
  constructor(url) { this.ws = new WebSocket(url); this.seq = 0; this.pending = new Map() }
  async open() {
    await new Promise((res, rej) => {
      this.ws.addEventListener('open', res, { once: true })
      this.ws.addEventListener('error', rej, { once: true })
    })
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const it = this.pending.get(msg.id); this.pending.delete(msg.id)
        if (msg.error) it.reject(new Error(JSON.stringify(msg.error))); else it.resolve(msg.result)
      }
    })
    return this
  }
  send(method, params = {}) {
    const id = ++this.seq
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }
  async evaluate(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400))
    return r.result ? r.result.value : undefined
  }
}

try {
  await waitDevtools()
  const target = await (await fetch('http://127.0.0.1:' + PORT + '/json/new?about:blank', { method: 'PUT' })).json()
  const cdp = await new Cdp(target.webSocketDebuggerUrl).open()
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')

  const url = pathToFileURL(htmlAbs).href
  await cdp.send('Page.navigate', { url })

  // 等 mermaid 渲染完成
  let ready = false
  for (let i = 0; i < 200; i++) {
    await sleep(250)
    const done = await cdp.evaluate('window.__renderDone === true').catch(() => false)
    if (done) { ready = true; break }
  }
  if (!ready) console.warn('WARN: mermaid render flag not set, exporting anyway')
  await sleep(1200)

  // 检查是否有渲染失败的图
  const errs = await cdp.evaluate("Array.from(document.querySelectorAll('.mermaid-box pre')).map(p=>p.textContent.slice(0,120))").catch(() => [])
  if (errs && errs.length) console.warn('WARN diagram errors: ' + JSON.stringify(errs).slice(0, 600))

  const pdf = await cdp.send('Page.printToPDF', {
    printBackground: true,
    paperWidth: 8.27,      // A4
    paperHeight: 11.69,
    marginTop: 0.63, marginBottom: 0.63, marginLeft: 0.55, marginRight: 0.55,
    displayHeaderFooter: false,
    preferCSSPageSize: true,
  })
  writeFileSync(pdfAbs, Buffer.from(pdf.data, 'base64'))
  console.log('PDF: ' + pdfAbs)
} finally {
  chrome.kill()
}
