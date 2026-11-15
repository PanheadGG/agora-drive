// 从 layout.mjs 直接生成 SVG。几何完全自控：正交折线、统一字号、圆角矩形。
// 输出为纯 <text>（无 foreignObject），可直接嵌入论文与 Office。
import { writeFileSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { FIGURES, C, EDGE, FONT_NODE, FONT_EDGE, CANVAS_W } from './layout.mjs'

const DIR = dirname(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1')
const OUT = resolve(DIR, '../../figures/drawio')
mkdirSync(OUT, { recursive: true })

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const byId = (nodes) => Object.fromEntries(nodes.map((n) => [n.id, n]))

/** 计算从 a 到 b 的正交路径（优先从下方/上方出，再左右） */
function hubLeftRoute(a, b) {
  // 从源左侧出 → 落到两列之间的竖直空隙 → 下行 → 从目标顶边进入
  const sx = a.x, sy = a.y + a.h / 2
  const laneX = a.x - 26
  const tx = b.x + b.w / 2, ty = b.y
  return [[sx, sy], [laneX, sy], [laneX, ty - 18], [tx, ty - 18], [tx, ty]]
}

function gutterRoute(a, b) {
  // 从源底边出，落到列间竖直空隙（两列之间），再横向进入目标左边
  const laneX = (a.x + a.w + b.x) / 2
  const sy = a.y + a.h
  const ex = b.x
  const ey = b.y + b.h / 2
  return [[a.x + a.w / 2, sy], [a.x + a.w / 2, sy + 14], [laneX, sy + 14], [laneX, ey], [ex, ey]]
}

function sideRoute(a, b, side) {
  // 侧向绕行：从源的目标侧边出，走列间空隙，再进入目标的同侧边
  if (side === 'right') {
    const sx = a.x + a.w, sy = a.y + a.h / 2
    const ex = b.x + b.w, ey = b.y + b.h / 2
    const lane = Math.max(sx, ex) + 26
    return [[sx, sy], [lane, sy], [lane, ey], [ex, ey]]
  }
  const sx = a.x, sy = a.y + a.h / 2
  const ex = b.x, ey = b.y + b.h / 2
  const lane = Math.min(sx, ex) - 26
  return [[sx, sy], [lane, sy], [lane, ey], [ex, ey]]
}

function route(a, b) {
  const acx = a.x + a.w / 2, acy = a.y + a.h / 2
  const bcx = b.x + b.w / 2, bcy = b.y + b.h / 2

  // 判断两框在水平/垂直方向是否重叠（重叠才用直连，否则绕行）
  const overlapX = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
  const overlapY = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)

  // 同列且垂直分离：垂直直连
  if (overlapX > 12 && Math.abs(acx - bcx) < 24 && Math.abs(acy - bcy) > 20) {
    const down = bcy > acy
    return [[acx, down ? a.y + a.h : a.y], [bcx, down ? b.y : b.y + b.h]]
  }
  // 同行且水平分离：水平直连
  if (overlapY > 12 && Math.abs(acy - bcy) < 24 && Math.abs(acx - bcx) > 20) {
    const right = bcx > acx
    return [[right ? a.x + a.w : a.x, acy], [right ? b.x : b.x + b.w, bcy]]
  }
  // 对角：从最近的竖直边出，绕中间带再进入目标竖直边
  const down = bcy > acy
  const startY = down ? a.y + a.h : a.y
  const endY = down ? b.y : b.y + b.h
  // 若源与目标水平不重叠，走「出边 → 中间横段 → 进边」
  const bend = typeof arguments[2] === 'number' ? arguments[2] : 0.5
  const midY = startY + (endY - startY) * bend
  return [[acx, startY], [acx, midY], [bcx, midY], [bcx, endY]]
}

/** 边标签位置：路径中最长水平段的中心 */
function labelAt(points) {
  let best = null, bestLen = -1
  for (let i = 0; i < points.length - 1; i++) {
    const [x1, y1] = points[i], [x2, y2] = points[i + 1]
    const len = Math.abs(x2 - x1) + Math.abs(y2 - y1)
    const horiz = Math.abs(y2 - y1) < 1
    if (len > bestLen && (horiz || points.length === 2)) { bestLen = len; best = [(x1 + x2) / 2, (y1 + y2) / 2] }
  }
  if (!best) {
    const [x1, y1] = points[0], [x2, y2] = points[points.length - 1]
    best = [(x1 + x2) / 2, (y1 + y2) / 2]
  }
  return best
}

for (const fig of FIGURES) {
  const d = fig.data
  // 画布高度不得小于内容包围盒（下留 16px），避免节点被裁切
  const bottom = Math.max(...d.nodes.map((n) => n.y + n.h), 0) + 16
  if (d.height < bottom) d.height = Math.ceil(bottom / 2) * 2
  const nodes = byId(d.nodes)
  const parts = []

  // 分组容器（先画，位于底层）
  for (const g of d.groups || []) {
    const c = C[g.style] || C.infra
    parts.push(`  <rect x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="10" ry="10" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.2"/>`)
    parts.push(`  <text x="${g.x + 12}" y="${g.y + 18}" font-family="Helvetica, Arial, sans-serif" font-size="${FONT_NODE - 2}" font-weight="bold" fill="#2D3748">${esc(g.label)}</text>`)
  }

  // 边（先于节点绘制，避免压住节点文字）
  for (const e of d.edges) {
    const a = nodes[e.from], b = nodes[e.to]
    if (!a || !b) { console.warn('missing node for edge ' + e.from + '->' + e.to); continue }
    const pts = e.way === 'gutter' ? gutterRoute(a, b) : e.way === 'hub-left' ? hubLeftRoute(a, b) : (e.way ? sideRoute(a, b, e.way) : route(a, b, e.bend))
    const pathD = pts.map((p, i) => (i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ')
    parts.push(`  <path d="${pathD}" fill="none" stroke="${EDGE}" stroke-width="1.4"${e.dashed ? ' stroke-dasharray="5 3"' : ''} marker-end="url(#arrow)"/>`)
    if (e.label) {
      const [lx, ly] = labelAt(pts)
      const w = e.label.length * 5.6 + 8
      parts.push(`  <rect x="${(lx - w / 2).toFixed(1)}" y="${(ly - 9).toFixed(1)}" width="${w.toFixed(1)}" height="16" fill="#FFFFFF" opacity="0.94"/>`)
      parts.push(`  <text x="${lx.toFixed(1)}" y="${ly.toFixed(1)}" text-anchor="middle" dominant-baseline="middle" font-family="Helvetica, Arial, sans-serif" font-size="${FONT_EDGE}" fill="#2D3748">${esc(e.label)}</text>`)
    }
  }

  // 节点
  for (const n of d.nodes) {
    const c = C[n.style] || C.start
    if (n.dec) {
      const cx = n.x + n.w / 2, cy = n.y + n.h / 2
      parts.push(`  <polygon points="${cx},${n.y} ${n.x + n.w},${cy} ${cx},${n.y + n.h} ${n.x},${cy}" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.5"/>`)
    } else if (n.cyl) {
      const ry = 8
      const x = n.x, y = n.y, w = n.w, h = n.h
      parts.push(`  <path d="M${x} ${y + ry} a${w / 2} ${ry} 0 0 1 ${w} 0 v${h - ry * 2} a${w / 2} ${ry} 0 0 1 -${w} 0 Z" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.5"/>`)
      parts.push(`  <ellipse cx="${x + w / 2}" cy="${y + ry}" rx="${w / 2}" ry="${ry}" fill="none" stroke="${c.stroke}" stroke-width="1.2"/>`)
    } else {
      parts.push(`  <rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" rx="8" ry="8" fill="${c.fill}" stroke="${c.stroke}" stroke-width="1.5"/>`)
    }
    // 多行文字：按行居中
    const lineH = (n.lines[0].size || FONT_NODE) * 1.28
    const totalH = n.lines.reduce((s, l) => s + (l.size || FONT_NODE) * 1.28, 0)
    let cy = n.y + n.h / 2 - totalH / 2
    const maxChars = n.lines.reduce((m, l) => Math.max(m, l.t.length), 1)
    for (const l of n.lines) {
      let size = l.size || FONT_NODE
      // 文字过长时按可用宽度收缩，保持左右各 8px 内边距
      const avail = n.w - 16
      const est = l.t.length * size * 0.55
      if (est > avail) size = Math.max(8, Math.floor(size * avail / est))
      if (l.size && l.size < FONT_NODE) size = Math.min(size, l.size)
      const lh = size * 1.28
      cy += lh / 2
      parts.push(`  <text x="${n.x + n.w / 2}" y="${cy.toFixed(1)}" text-anchor="middle" dominant-baseline="central" font-family="Helvetica, Arial, sans-serif" font-size="${size}"${l.bold ? ' font-weight="bold"' : ''} fill="#1A202C">${esc(l.t)}</text>`)
      cy += lh / 2
    }
  }

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS_W} ${d.height}" width="${CANVAS_W}" height="${d.height}">
  <defs>
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="${EDGE}"/>
    </marker>
  </defs>
${parts.join('\n')}
</svg>
`
  writeFileSync(resolve(OUT, fig.key + '.svg'), svg, 'utf8')
  console.log('write ' + fig.key + '.svg  ' + svg.length + 'B  ' + CANVAS_W + 'x' + d.height)
}
console.log('\nSVG output in ' + OUT)
