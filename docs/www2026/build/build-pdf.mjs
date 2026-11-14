// Markdown → 双栏 HTML → Chrome printToPDF
// 用法: node build-pdf.mjs <in.md> <out.pdf> [--lang zh|en]
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { marked } from 'marked'

const [, , inMd, outPdf, ...rest] = process.argv
if (!inMd || !outPdf) {
  console.error('usage: node build-pdf.mjs <in.md> <out.pdf> [--lang zh|en]')
  process.exit(1)
}
const langFlag = rest.indexOf('--lang')
const lang = langFlag >= 0 ? rest[langFlag + 1] : 'en'
const src = resolve(inMd)
const out = resolve(outPdf)
mkdirSync(dirname(out), { recursive: true })

const md = readFileSync(src, 'utf8')

// ---------- 1. mermaid 块抽出并占位 ----------
const mermaidBlocks = []
const withPlaceholders = md.replace(/^```mermaid\n([\s\S]*?)^```$/gm, (_all, code) => {
  mermaidBlocks.push(code.trim())
  return '\nMERMAIDPLACEHOLDER' + (mermaidBlocks.length - 1) + 'X\n'
})

// ---------- 2. Markdown → HTML ----------
marked.setOptions({ gfm: true, breaks: false })
let html = marked.parse(withPlaceholders)
html = html.replace(/<p>\s*MERMAIDPLACEHOLDER(\d+)X\s*<\/p>/g, (_m, i) => '\n<div class="figure mermaid-box" data-idx="' + i + '"></div>\n')

// ---------- 3. 图片：路径转绝对 + 包进 figure ----------
const mdDir = dirname(src)
html = html.replace(/(<img[^>]*?src=")([^"]+)(")/g, (_m, pre, s, post) => {
  if (/^(https?:|data:|file:)/i.test(s)) return _m
  const abs = resolve(mdDir, s).replace(/\\/g, '/')
  return pre + 'file:///' + abs + post
})
// markdown 的 ![]() 会变成裸 <p><img></p>，必须包进 .figure 才能套用尺寸约束
html = html.replace(/<p>\s*(<img[^>]*>)\s*<\/p>/g, '<div class="figure">$1</div>')
// 图注：整段只有一个 <em>，且以「图 N」/「Figure N」开头
html = html.replace(/<p>\s*<em>([\s\S]*?)<\/em>\s*<\/p>/g, (m, inner) => {
  if (!/^\s*(图|Figure)\s*\d+/.test(inner)) return m
  return '<p class="caption"><em>' + inner + '</em></p>'
})

// ---------- 4. 抽题目区 ----------
const h1m = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)
const titleHtml = h1m ? h1m[1].replace(/<[^>]+>/g, '').trim() : 'Demo Paper'
html = html.replace(/<h1[^>]*>[\s\S]*?<\/h1>/, '')

// 开头的 blockquote（作者/双盲声明）+ 紧随的第一个 hr 之前的段落 = 题头区
let headerHtml = ''
const headMatch = html.match(/^\s*<blockquote>([\s\S]*?)<\/blockquote>/)
if (headMatch) {
  headerHtml = headMatch[1]
  html = html.replace(/^\s*<blockquote>[\s\S]*?<\/blockquote>/, '')
}
// 第一个 <hr> 之前若是段落/关键词，也并入题头
const kwSplit = html.indexOf('<hr>')
if (kwSplit > 0 && kwSplit < 900) {
  headerHtml += html.slice(0, kwSplit)
  html = html.slice(kwSplit + 4)
}
html = html.replace(/^\s*<hr>\s*/, '')

// 题头里的 **Demo Paper — ...** / **演示论文 — ...** 拎出来当 venue 行
let venue = ''
headerHtml = headerHtml.replace(/<p>\s*<strong>((?:Demo Paper|演示论文)[\s\S]*?)<\/strong>\s*<\/p>/, (_m, v) => {
  venue = v.replace(/<[^>]+>/g, '')
  return ''
})
// 有些版本 venue 行落在 titleblock 之外（在 body 开头），也一并提出来
html = html.replace(/^\s*<p>\s*<strong>((?:Demo Paper|演示论文)[\s\S]*?)<\/strong>\s*<\/p>/, (_m, v) => {
  if (!venue) venue = v.replace(/<[^>]+>/g, '')
  return ''
})
headerHtml = headerHtml.replace(/^\s*<hr>\s*$/, '')

// ---------- 5. 样式 ----------
const FONT = lang === 'zh'
  ? "'Source Han Serif SC','Noto Serif CJK SC','SimSun','Songti SC',serif"
  : "'Linux Libertine','Times New Roman',Times,serif"
const SANS = lang === 'zh'
  ? "'Source Han Sans SC','Microsoft YaHei','PingFang SC',sans-serif"
  : "'Inter','Helvetica Neue',Arial,sans-serif"

const css = `
@page { size: A4; margin: 14mm 13mm 15mm 13mm; }
* { box-sizing: border-box; }
html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
body {
  margin: 0; font-family: ${FONT}; font-size: ${lang === 'zh' ? '8.8pt' : '8.5pt'}; line-height: 1.42;
  color: #111; text-align: justify; hyphens: auto; word-break: break-word;
}
.titleblock {
  text-align: center; margin: 0 0 9pt; padding-bottom: 7pt;
  border-bottom: 0.6pt solid #999;
}
.titleblock h1 { font-family: ${SANS}; font-size: 16.5pt; line-height: 1.2; margin: 0 0 7pt; font-weight: 700; }
.titleblock .meta { font-size: 8.4pt; line-height: 1.55; color: #333; }
.titleblock .meta p { margin: 0 0 2pt; text-align: center; }
/* 摘要标题若被顶栏吸收，这里保证不误用居中 */
.titleblock .meta .caption { text-align: center; }
.titleblock .venue { font-size: 8.2pt; color: #444; margin: 0 0 5pt; font-weight: 600; letter-spacing: 0.3px; }

.body { column-count: 2; column-gap: 7mm; column-fill: auto; }

h2 { font-family: ${SANS}; font-size: 9.8pt; font-weight: 700; margin: 9pt 0 3.5pt; break-after: avoid; }
h3 { font-family: ${SANS}; font-size: 8.8pt; font-weight: 700; margin: 7pt 0 2.5pt; break-after: avoid; }
h4 { font-family: ${SANS}; font-size: 8.4pt; font-weight: 700; margin: 6pt 0 2pt; break-after: avoid; }
p { margin: 0 0 3.8pt; orphans: 2; widows: 2; }
ul, ol { margin: 0 0 4pt; padding-left: 11pt; }
li { margin: 0 0 2pt; }
strong { font-weight: 700; }
a { color: #0b4fa8; text-decoration: none; }
hr { display: none; }

blockquote {
  margin: 0 0 4pt; padding: 2.5pt 5pt; border-left: 2pt solid #c8d4e4;
  background: #f6f8fb; font-size: 8pt; color: #333;
}
blockquote p { margin: 0 0 1.5pt; }

code { font-family: 'DejaVu Sans Mono', Consolas, monospace; font-size: 7.5pt; background: #f2f4f7; padding: 0 1.5pt; border-radius: 2px; }
pre {
  background: #f7f8fa; border: 0.4pt solid #e3e6ea; border-radius: 3px;
  padding: 4pt 5pt; overflow: hidden; break-inside: avoid;
  font-size: 7.2pt; line-height: 1.32; margin: 0 0 4.5pt;
}
pre code { background: none; padding: 0; font-size: 7.2pt; }

table { width: 100%; border-collapse: collapse; font-size: 7.4pt; margin: 0 0 5pt; break-inside: avoid; table-layout: fixed; }
th, td {
  border: 0.4pt solid #d7dbe0; padding: 2pt 3pt; text-align: left; vertical-align: top;
  word-break: normal; overflow-wrap: break-word; hyphens: none;
}
th { background: #eef2f7; font-weight: 700; font-family: ${SANS}; font-size: 7.2pt; }

/* 图：限制高度，避免截图撑爆版面 */
.figure { break-inside: avoid; page-break-inside: avoid; margin: 5pt 0 2pt; text-align: center; }
/* 截图默认按栏宽显示，但限制高度，避免一张图占掉半页 */
.figure img {
  display: block; margin: 0 auto;
  max-width: 100%; max-height: 46mm; width: auto; height: auto;
  border: 0.4pt solid #dcdfe4; border-radius: 2px;
}
.mermaid-box { min-height: 20pt; }
/* 流程图尽量撑满栏宽 */
.mermaid-box svg {
  display: block; margin: 0 auto;
  width: 100% !important; max-width: 100% !important;
  height: auto !important; max-height: 76mm;
}

/* 图注：只对「整段就是一个 em」的段落生效（参考文献里的 em 在句中，不受影响） */
p.caption { font-size: 7.4pt; line-height: 1.35; text-align: center; color: #333; margin: 0 0 6pt; }

/* 表格单元格内的截图：强制限高，避免撑爆版面 */
td img, th img {
  display: block; margin: 0 auto;
  max-width: 100% !important; max-height: 40mm !important;
  width: auto !important; height: auto !important;
}
`

// ---------- 6. HTML ----------
// mermaid 必须用绝对 file:// 路径：HTML 与 node_modules 不在同一目录
const MERMAID_JS = 'file:///' + resolve('node_modules/mermaid/dist/mermaid.min.js').replace(/\\/g, '/')
const htmlDoc = `<!doctype html>
<html lang="${lang}">
<head><meta charset="utf-8"><title>${titleHtml}</title><style>${css}</style></head>
<body>
<script id="mermaid-src" type="application/json">${JSON.stringify(mermaidBlocks)}</script>
<div class="titleblock">
  <h1>${titleHtml}</h1>
  ${venue ? '<div class="venue">' + venue + '</div>' : ''}
  <div class="meta">${headerHtml}</div>
</div>
<div class="body">
${html}
</div>
<script src="${MERMAID_JS}" onload="window.__mermaidLoaded=true"></script>
<script>
// 等待 UMD 脚本真正执行完（onload 之后再等一帧），避免 mermaid is not defined
async function waitMermaid() {
  for (let i = 0; i < 400; i++) {
    if (typeof window.mermaid !== 'undefined' && window.mermaid.initialize) return window.mermaid
    await new Promise((r) => setTimeout(r, 25))
  }
  throw new Error('mermaid bundle did not load')
}
;(async function () {
  try {
    const mermaid = await waitMermaid()
    mermaid.initialize({
      startOnLoad: false, theme: 'neutral',
      flowchart: { curve: 'basis', padding: 4, nodeSpacing: 12, rankSpacing: 16, htmlLabels: true, useMaxWidth: true },
      sequence: { actorMargin: 14, boxMargin: 4, messageFontSize: 7, noteFontSize: 7, useMaxWidth: true },
      er: { entityPadding: 4, minEntityWidth: 50, useMaxWidth: true },
      fontFamily: 'Inter, Arial, sans-serif', fontSize: 9, useMaxWidth: true
    })
    const blocks = JSON.parse(document.getElementById('mermaid-src').textContent)
    const boxes = Array.prototype.slice.call(document.querySelectorAll('.mermaid-box'))
    for (let i = 0; i < boxes.length; i++) {
      const box = boxes[i]
      const code = blocks[Number(box.dataset.idx)]
      try {
        const out = await mermaid.render('mmd' + i, code)
        box.innerHTML = out.svg
        const svg = box.querySelector('svg')
        svg.removeAttribute('height')
        svg.style.width = '100%'
        svg.style.height = 'auto'
        svg.style.maxHeight = '78mm'
      } catch (e) {
        box.innerHTML = '<pre style="color:#b00;font-size:7pt">diagram error: ' + String(e && e.message || e).slice(0, 160) + '</pre>'
      }
    }
  } catch (e) {
    document.getElementById('mermaid-src').insertAdjacentHTML('afterend',
      '<pre style="color:#b00">mermaid init failed: ' + String(e && e.message || e) + '</pre>')
  }
  window.__renderDone = true
})()
</script>
</body></html>`

const htmlPath = out.replace(/\.pdf$/i, '.html')
writeFileSync(htmlPath, htmlDoc, 'utf8')
console.log('HTML: ' + htmlPath)
console.log('title: ' + titleHtml)
console.log('mermaid blocks: ' + mermaidBlocks.length)
console.log('header chars: ' + headerHtml.length + ', venue: ' + JSON.stringify(venue.slice(0, 60)))
