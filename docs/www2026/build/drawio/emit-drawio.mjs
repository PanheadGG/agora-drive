// 从 layout.mjs 生成 .drawio（mxGraph XML）——保留为可编辑中间产物。
import { writeFileSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { FIGURES, C, EDGE, FONT_NODE, CANVAS_W } from './layout.mjs'

const DIR = dirname(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1')
mkdirSync(DIR, { recursive: true })

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const html = (lines) => lines.map((l) =>
  l.size ? `<font style="font-size:${l.size}px">${esc(l.t)}</font>` : esc(l.t)
).join('<br>')

function nodeStyle(styleKey, extra = '') {
  const c = C[styleKey] || C.start
  return [
    'rounded=1', 'whiteSpace=wrap', 'html=1', 'arcSize=10', 'absoluteArcSize=1',
    `fillColor=${c.fill}`, `strokeColor=${c.stroke}`, 'strokeWidth=1.5',
    `fontSize=${FONT_NODE}`, 'fontColor=#1A202C', 'fontFamily=Helvetica',
    'align=center', 'verticalAlign=middle', 'spacing=4',
    extra,
  ].filter(Boolean).join(';') + ';'
}

function groupStyle(styleKey) {
  const c = C[styleKey] || C.infra
  return [
    'rounded=1', 'whiteSpace=wrap', 'html=1', 'arcSize=6',
    `fillColor=${c.fill}`, `strokeColor=${c.stroke}`, 'strokeWidth=1', 'dashed=0',
    'verticalAlign=top', 'align=left', 'spacingLeft=8', 'spacingTop=3',
    `fontSize=${FONT_NODE - 2}`, 'fontStyle=1', 'fontColor=#2D3748',
    'container=1', 'collapsible=0', 'fontFamily=Helvetica',
  ].join(';') + ';'
}

function shapeStyle(n) {
  if (n.dec) {
    const c = C.decide
    return `rhombus;whiteSpace=wrap;html=1;fillColor=${c.fill};strokeColor=${c.stroke};strokeWidth=1.5;fontSize=${FONT_NODE};fontFamily=Helvetica;`
  }
  if (n.cyl) {
    const c = C[n.style] || C.store
    return `shape=cylinder3;boundedLbl=1;backgroundOutline=1;size=8;whiteSpace=wrap;html=1;fillColor=${c.fill};strokeColor=${c.stroke};strokeWidth=1.5;fontSize=${FONT_NODE};fontFamily=Helvetica;`
  }
  return nodeStyle(n.style)
}

for (const fig of FIGURES) {
  const d = fig.data
  const bottom = Math.max(...d.nodes.map((n) => n.y + n.h), 0) + 16
  if (d.height < bottom) d.height = Math.ceil(bottom / 2) * 2
  const cells = []
  let id = 0
  const nid = (p) => p + (++id)

  for (const g of d.groups || []) cells.push({ id: g.id, value: g.label, style: groupStyle(g.style), vertex: true, x: g.x, y: g.y, w: g.w, h: g.h })
  for (const n of d.nodes) cells.push({ id: n.id, value: html(n.lines), style: shapeStyle(n), vertex: true, x: n.x, y: n.y, w: n.w, h: n.h })
  for (const e of d.edges) {
    const c = C.edge || EDGE
    const st = [
      'edgeStyle=orthogonalEdgeStyle', 'rounded=0', 'orthogonalLoop=1', 'jettySize=auto', 'html=1',
      `strokeColor=${EDGE}`, 'strokeWidth=1.4',
      'fontSize=11', 'fontColor=#2D3748', 'fontFamily=Helvetica', 'labelBackgroundColor=#FFFFFF',
      e.dashed ? 'dashed=1' : '',
      'endArrow=block', 'endFill=1',
    ].filter(Boolean).join(';') + ';'
    cells.push({ id: nid('e'), value: esc(e.label || ''), style: st, edge: true, source: e.from, target: e.to })
  }

  const body = cells.map((c) => {
    const a = `id="${c.id}" value="${c.value}" style="${esc(c.style)}"`
    if (c.edge) return `        <mxCell ${a} edge="1" parent="1" source="${c.source}" target="${c.target}">\n          <mxGeometry relative="1" as="geometry" />\n        </mxCell>`
    return `        <mxCell ${a} vertex="1" parent="1">\n          <mxGeometry x="${c.x}" y="${c.y}" width="${c.w}" height="${c.h}" as="geometry" />\n        </mxCell>`
  }).join('\n')

  const xml = `<mxfile host="drawio" version="32.6.10">
  <diagram id="${d.id}" name="${fig.page}">
    <mxGraphModel dx="${CANVAS_W}" dy="${d.height}" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="${CANVAS_W}" pageHeight="${d.height}" math="0" shadow="0">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />
${body}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
`
  writeFileSync(resolve(DIR, fig.key + '.drawio'), xml, 'utf8')
  console.log('write ' + fig.key + '.drawio  (' + cells.length + ' cells)')
}
console.log('\ndrawio sources in ' + DIR)
