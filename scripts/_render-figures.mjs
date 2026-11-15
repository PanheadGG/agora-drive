// 把论文里的 mermaid 图渲染成紧凑 SVG（面向 ACM 双栏排版）
// 用法：node scripts/_render-figures.mjs
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createServer } from 'node:http'

const ROOT = 'docs/www2026'
const TMP = ROOT + '/_tmp'
const OUT = ROOT + '/figures'
mkdirSync(OUT, { recursive: true })

const FILES = [
  { src: ROOT + '/WWW2026_Demo_AgentCloudDisk_EN.md', prefix: 'en' },
  { src: ROOT + '/WWW2026_Demo_AgentCloudDisk_ZH.md', prefix: 'zh' },
]

function extractBlocks(path) {
  const text = readFileSync(path, 'utf8')
  const blocks = []
  const re = /^```mermaid\n([\s\S]*?)^```/gm
  let m
  while ((m = re.exec(text))) {
    const code = m[1].trim()
    const header = code.split('\n')[0].trim()
    const kind = header.startsWith('sequenceDiagram') ? 'seq'
      : header.startsWith('erDiagram') ? 'er'
      : 'flow'
    blocks.push({ code, kind, header })
  }
  return blocks
}

// 双栏适配：流程图/ER 用窄宽度；时序图多参与者用全宽
const TUNING = {
  flow: { scale: 1.0, fontSize: 13, nodeSpacing: 26, rankSpacing: 34, padding: 6 },
  er: { scale: 1.0, fontSize: 12, nodeSpacing: 22, rankSpacing: 40, padding: 6 },
  seq: { scale: 1.0, fontSize: 12, nodeSpacing: 18, rankSpacing: 26, padding: 6 },
}

const bundle = readFileSync(TMP + '/mermaid.min.js')
const jobs = []
for (const f of FILES) {
  const blocks = extractBlocks(f.src)
  blocks.forEach((b, i) => jobs.push({ ...b, idx: i + 1, prefix: f.prefix, key: f.prefix + '-' + String(i + 1).padStart(2, '0') }))
}
console.log('blocks to render:', jobs.length)

const html = `<!doctype html><html><head><meta charset="utf-8">
<style>body{margin:0;background:#fff;font-family:"Noto Sans CJK SC","Microsoft YaHei",Arial,sans-serif}</style>
</head><body><div id="host"></div>
<script src="/mermaid.min.js"></script>
<script>
window.__render = async function(code, kind) {
  const tuning = ${JSON.stringify(TUNING)}[kind] || ${JSON.stringify(TUNING.flow)};
  mermaid.initialize({
    startOnLoad: false,
    theme: 'neutral',
    securityLevel: 'loose',
    fontFamily: '"Noto Sans CJK SC","Microsoft YaHei",Arial,sans-serif',
    fontSize: tuning.fontSize,
    flowchart: { htmlLabels: false, curve: 'basis', nodeSpacing: tuning.nodeSpacing, rankSpacing: tuning.rankSpacing, padding: tuning.padding, useMaxWidth: false },
    sequence: { useMaxWidth: false, wrap: true, width: 160, height: 40, boxMargin: 8, noteMargin: 8, messageMargin: 26, mirrorActors: false, actorFontSize: 12, messageFontSize: 11, noteFontSize: 11 },
    er: { useMaxWidth: false, entityPadding: 10, minEntityWidth: 70, minEntityHeight: 40, fontSize: 11 },
  });
  const { svg } = await mermaid.render('g' + Math.random().toString(36).slice(2), code);
  return svg;
};
</script></body></html>`

const server = createServer((req, res) => {
  if (req.url.startsWith('/mermaid.min.js')) {
    res.writeHead(200, { 'Content-Type': 'text/javascript' })
    res.end(bundle)
    return
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
  res.end(html)
})
await new Promise((r) => server.listen(8899, '127.0.0.1', r))
console.log('server on 8899')

const target = await (await fetch('http://127.0.0.1:9222/json/new?about:blank', { method: 'PUT' })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
let seq = 0
const pending = new Map()
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id); pending.delete(m.id)
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
  }
})
await new Promise((r, j) => { ws.addEventListener('open', r, { once: true }); ws.addEventListener('error', j, { once: true }) })
const send = (method, params = {}) => { const id = ++seq; ws.send(JSON.stringify({ id, method, params })); return new Promise((res, rej) => pending.set(id, { resolve: res, reject: rej })) }
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400))
  return r.result?.value
}

await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: 'http://127.0.0.1:8899/' })
await new Promise((r) => setTimeout(r, 2500))

const results = []
for (const job of jobs) {
  try {
    const svg = await evaluate('window.__render(' + JSON.stringify(job.code) + ',' + JSON.stringify(job.kind) + ')')
    if (!svg || !svg.startsWith('<svg')) throw new Error('no svg returned')
    const trimmed = svg
      // 统一去掉写死的宽高样式，改为可缩放的 viewBox 形式
      .replace(/style="[^"]*max-width:[^"]*"/g, '')
      .replace(/height="[^"]*"/, '')
      .replace(/width="[^"]*"/, '')
      .replace('<svg ', '<svg preserveAspectRatio="xMidYMid meet" ')
    const file = OUT + '/' + job.key + '.svg'
    writeFileSync(file, trimmed)
    const w = (svg.match(/width="([\d.]+)"/) || [])[1]
    const h = (svg.match(/height="([\d.]+)"/) || [])[1]
    results.push({ key: job.key, kind: job.kind, header: job.header, w, h, bytes: trimmed.length })
    console.log('OK  ' + job.key + '  ' + job.kind + '  ' + w + 'x' + h)
  } catch (err) {
    console.log('FAIL ' + job.key + ' :: ' + String(err.message).slice(0, 200))
  }
}
writeFileSync(OUT + '/_meta.json', JSON.stringify(results, null, 2))
ws.close()
server.close()
process.exit(0)
