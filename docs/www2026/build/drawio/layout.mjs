// 六张图的唯一布局定义：drawio 与 SVG 两个导出器都从这里取数据，保证两者一致。
// 图围绕「Agent 与网盘如何结合」组织，不描述网盘基础功能。

export const CANVAS_W = 760

export const C = {
  client:  { fill: '#EAF2FB', stroke: '#3B7DD8' },
  service: { fill: '#E8F5EC', stroke: '#2E9E5B' },
  agent:   { fill: '#FFF4E5', stroke: '#E08B2D' },
  infra:   { fill: '#F2EDF9', stroke: '#7E57C2' },
  store:   { fill: '#EDF7F7', stroke: '#0E8A8A' },
  start:   { fill: '#FFFFFF', stroke: '#5A6472' },
  decide:  { fill: '#FFF9E0', stroke: '#C9A227' },
  warn:    { fill: '#FDECEA', stroke: '#C0392B' },
}
export const EDGE = '#4A5568'
export const FONT_NODE = 12
export const FONT_SMALL = 10
export const FONT_EDGE = 11

// 图 1：Agent 与网盘的三条结合面
export const fig1 = {
  id: 'fig1-binding', height: 392,
  groups: [
    { id: 'g1', label: 'Owner cloud drive — the substrate the agent stands on', style: 'store', x: 16, y: 8, w: 688, h: 132 },
    { id: 'g2', label: 'Agent — one per owner', style: 'agent', x: 16, y: 156, w: 688, h: 132 },
    { id: 'g3', label: 'What leaves the drive', style: 'service', x: 16, y: 304, w: 688, h: 76 },
  ],
  nodes: [
    { id: 'own', style: 'store', x: 44, y: 42, w: 148, h: 60, lines: [{ t: 'Owner drive' }, { t: 'raw files', size: FONT_SMALL }] },
    { id: 'agg', style: 'service', x: 224, y: 42, w: 148, h: 60, lines: [{ t: 'Aggregates' }, { t: 'k-anonymity ≥ 3', size: FONT_SMALL }] },
    { id: 'rcpt', style: 'infra', x: 404, y: 42, w: 148, h: 60, lines: [{ t: 'Build receipt' }, { t: 'source hashes', size: FONT_SMALL }] },
    { id: 'obs', style: 'start', x: 584, y: 42, w: 96, h: 60, lines: [{ t: 'Observable' }, { t: 'in form', size: FONT_SMALL }] },

    { id: 'intent', style: 'agent', x: 44, y: 190, w: 148, h: 60, lines: [{ t: 'Intent' }, { t: '「one sentence」', size: FONT_SMALL }] },
    { id: 'tmpl', style: 'agent', x: 224, y: 190, w: 148, h: 60, lines: [{ t: 'Template' }, { t: 'rule then LLM', size: FONT_SMALL }] },
    { id: 'scope', style: 'agent', x: 404, y: 190, w: 148, h: 60, lines: [{ t: 'Scope' }, { t: 'ticked folders', size: FONT_SMALL }] },
    { id: 'evt', style: 'agent', x: 584, y: 190, w: 96, h: 60, lines: [{ t: 'Progress' }, { t: 'per file', size: FONT_SMALL }] },

    { id: 'prod', style: 'service', x: 224, y: 322, w: 148, h: 46, lines: [{ t: 'Data product' }] },
    { id: 'mkt', style: 'infra', x: 404, y: 322, w: 148, h: 46, lines: [{ t: 'Market' }] },
  ],
  edges: [
    { from: 'own', to: 'agg', label: 'read' },
    { from: 'agg', to: 'rcpt', label: 'bind' },
    { from: 'intent', to: 'tmpl' },
    { from: 'tmpl', to: 'scope' },
    { from: 'evt', to: 'scope' },
    { from: 'tmpl', to: 'agg', label: 'binds to' },
    { from: 'agg', to: 'prod' },
    { from: 'prod', to: 'mkt' },
  ],
}

// 图 2：单次请求的绑定与释放时序
export const fig2 = {
  id: 'fig2-bind', height: 620,
  groups: [
    { id: 'drive', label: 'Owner drive', style: 'store', x: 16, y: 8, w: 130, h: 596 },
    { id: 'agent', label: 'Agent', style: 'agent', x: 164, y: 8, w: 130, h: 596 },
    { id: 'model', label: 'LLM', style: 'infra', x: 312, y: 8, w: 130, h: 596 },
    { id: 'market', label: 'Market', style: 'service', x: 460, y: 8, w: 130, h: 596 },
    { id: 'audit', label: 'Receipt', style: 'start', x: 608, y: 8, w: 136, h: 596 },
  ],
  nodes: [
    { id: 'd1', style: 'store', x: 32, y: 44, w: 98, h: 44, lines: [{ t: 'files' }] },
    { id: 'd2', style: 'store', x: 32, y: 264, w: 98, h: 44, lines: [{ t: 'read chunk' }] },
    { id: 'd3', style: 'store', x: 32, y: 336, w: 98, h: 44, lines: [{ t: 'PII hit' }] },
    { id: 'd4', style: 'store', x: 32, y: 500, w: 98, h: 44, lines: [{ t: 'close' }] },
    { id: 'a1', style: 'agent', x: 180, y: 44, w: 98, h: 44, lines: [{ t: 'bind' }] },
    { id: 'a2', style: 'agent', x: 180, y: 116, w: 98, h: 44, lines: [{ t: 'scope' }] },
    { id: 'a3', style: 'agent', x: 180, y: 188, w: 98, h: 44, lines: [{ t: 'parse' }] },
    { id: 'a4', style: 'agent', x: 180, y: 336, w: 98, h: 44, lines: [{ t: 'exclude' }] },
    { id: 'a5', style: 'agent', x: 180, y: 408, w: 98, h: 44, lines: [{ t: 'aggregate' }] },
    { id: 'a6', style: 'agent', x: 180, y: 500, w: 98, h: 44, lines: [{ t: 'unbind' }] },
    { id: 'm1', style: 'infra', x: 328, y: 116, w: 98, h: 44, lines: [{ t: 'template' }] },
    { id: 'm2', style: 'infra', x: 328, y: 188, w: 98, h: 44, lines: [{ t: 'describe' }] },
    { id: 'k1', style: 'service', x: 476, y: 408, w: 98, h: 44, lines: [{ t: 'listing' }] },
    { id: 'k2', style: 'service', x: 476, y: 480, w: 98, h: 44, lines: [{ t: 'match' }] },
    { id: 'r1', style: 'start', x: 624, y: 408, w: 104, h: 44, lines: [{ t: 'receipt' }] },
    { id: 'r2', style: 'start', x: 624, y: 500, w: 104, h: 44, lines: [{ t: 'released' }] },
  ],
  edges: [
    { from: 'd1', to: 'a1' },
    { from: 'a1', to: 'a2' },
    { from: 'a2', to: 'm1', label: 'ask' },
    { from: 'm1', to: 'a3' },
    { from: 'a3', to: 'd2' },
    { from: 'd2', to: 'a4' },
    { from: 'a4', to: 'd3', label: 'logged' },
    { from: 'a4', to: 'a5' },
    { from: 'a5', to: 'm2' },
    { from: 'a5', to: 'k1', label: 'aggregates' },
    { from: 'k1', to: 'k2' },
    { from: 'a5', to: 'a6' },
    { from: 'a6', to: 'd4', label: 'unbind' },
    { from: 'd4', to: 'r2', label: 'released', dashed: true },
  ],
}

// 图 3：协同的绑定面（只交换分组统计）
export const fig3 = {
  id: 'fig3-collab', height: 420,
  groups: [
    { id: 'ia', label: 'Owner A drive', style: 'store', x: 16, y: 8, w: 200, h: 300 },
    { id: 'ib', label: 'Owner B drive', style: 'store', x: 544, y: 8, w: 200, h: 300 },
    { id: 'hub', label: 'Collaboration hub — sees statistics only', style: 'agent', x: 236, y: 8, w: 288, h: 300 },
  ],
  nodes: [
    { id: 'fa', style: 'store', x: 40, y: 52, w: 152, h: 52, lines: [{ t: 'raw files' }] },
    { id: 'ga', style: 'service', x: 40, y: 138, w: 152, h: 52, lines: [{ t: 'local aggregation' }] },
    { id: 'sa', style: 'agent', x: 40, y: 224, w: 152, h: 52, lines: [{ t: 'group stats' }, { t: 'no rows', size: FONT_SMALL }] },

    { id: 'fb', style: 'store', x: 568, y: 52, w: 152, h: 52, lines: [{ t: 'raw files' }] },
    { id: 'gb', style: 'service', x: 568, y: 138, w: 152, h: 52, lines: [{ t: 'local aggregation' }] },
    { id: 'sb', style: 'agent', x: 568, y: 224, w: 152, h: 52, lines: [{ t: 'group stats' }, { t: 'no rows', size: FONT_SMALL }] },

    { id: 'code', style: 'agent', x: 272, y: 52, w: 216, h: 52, lines: [{ t: 'session code + password' }] },
    { id: 'uq', style: 'service', x: 272, y: 138, w: 216, h: 52, lines: [{ t: 'union of group stats' }] },
    { id: 'gr', style: 'agent', x: 272, y: 224, w: 216, h: 52, lines: [{ t: 'product grows' }, { t: 'sources per group', size: FONT_SMALL }] },

    { id: 'pub', style: 'infra', x: 272, y: 344, w: 216, h: 56, lines: [{ t: 'Initiator publishes' }, { t: 'only the initiator', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 'fa', to: 'ga' },
    { from: 'ga', to: 'sa' },
    { from: 'sa', to: 'uq' },
    { from: 'fb', to: 'gb' },
    { from: 'gb', to: 'sb' },
    { from: 'sb', to: 'uq' },
    { from: 'code', to: 'ga', dashed: true, label: 'admit' },
    { from: 'code', to: 'gb', dashed: true },
    { from: 'uq', to: 'gr' },
    { from: 'gr', to: 'pub' },
  ],
}

// 图 4：绑定边界（哪些数据在网盘内、哪些可以出）
export const fig4 = {
  id: 'fig4-boundary', height: 420,
  groups: [
    { id: 'in', label: 'Inside the owner drive — never transmitted', style: 'store', x: 16, y: 8, w: 352, h: 396 },
    { id: 'out', label: 'Leaves the drive', style: 'agent', x: 392, y: 8, w: 352, h: 396 },
  ],
  nodes: [
    { id: 'f1', style: 'store', x: 32, y: 48, w: 320, h: 48, lines: [{ t: 'raw files, images, archives' }] },
    { id: 'f2', style: 'store', x: 32, y: 112, w: 320, h: 48, lines: [{ t: 'full documents' }] },
    { id: 'f3', style: 'store', x: 32, y: 176, w: 320, h: 48, lines: [{ t: 'plaintext secrets' }] },
    { id: 'f4', style: 'store', x: 32, y: 240, w: 320, h: 48, lines: [{ t: 'every individual row' }] },
    { id: 'f5', style: 'store', x: 32, y: 304, w: 320, h: 48, lines: [{ t: 'PII-bearing files' }] },
    { id: 'o1', style: 'agent', x: 408, y: 48, w: 320, h: 48, lines: [{ t: 'sentence to template choice' }] },
    { id: 'o2', style: 'agent', x: 408, y: 112, w: 320, h: 48, lines: [{ t: 'bounded extract, 1200 chars, 6 files' }] },
    { id: 'o3', style: 'agent', x: 408, y: 176, w: 320, h: 48, lines: [{ t: 'explicitly requested images' }] },
    { id: 'o4', style: 'service', x: 408, y: 240, w: 320, h: 48, lines: [{ t: 'group aggregates, k at least 3' }] },
    { id: 'o5', style: 'infra', x: 408, y: 304, w: 320, h: 48, lines: [{ t: 'build receipt with source hashes' }] },
  ],
  edges: [
    { from: 'f1', to: 'o2', label: 'extract' },
    { from: 'f2', to: 'o2', dashed: true },
    { from: 'f4', to: 'o4', label: 'aggregate' },
    { from: 'f5', to: 'o5', dashed: true, label: 'reason' },
  ],
}

// 图 5：Agent 的授权访问控制流
export const fig5 = {
  id: 'fig5-guard', height: 600,
  groups: [],
  nodes: [
    { id: 'q', style: 'start', x: 270, y: 8, w: 220, h: 44, lines: [{ t: 'Agent requests a file' }] },
    { id: 'c1', style: 'decide', dec: true, x: 282, y: 78, w: 196, h: 82, lines: [{ t: 'In ticked scope?' }] },
    { id: 'deny1', style: 'warn', x: 546, y: 92, w: 168, h: 54, lines: [{ t: 'Deny' }, { t: 'not in scope', size: FONT_SMALL }] },
    { id: 'c2', style: 'decide', dec: true, x: 282, y: 190, w: 196, h: 82, lines: [{ t: 'Owner of record?' }] },
    { id: 'deny2', style: 'warn', x: 546, y: 204, w: 168, h: 54, lines: [{ t: 'Deny' }, { t: 'other owner', size: FONT_SMALL }] },
    { id: 'c3', style: 'decide', dec: true, x: 282, y: 302, w: 196, h: 82, lines: [{ t: 'PII scan clean?' }] },
    { id: 'excl', style: 'warn', x: 546, y: 316, w: 168, h: 54, lines: [{ t: 'Exclude' }, { t: 'receipt reason', size: FONT_SMALL }] },
    { id: 'c4', style: 'decide', dec: true, x: 282, y: 414, w: 196, h: 76, lines: [{ t: 'Within model quota?' }] },
    { id: 'trim', style: 'infra', x: 546, y: 424, w: 168, h: 54, lines: [{ t: 'Trim' }, { t: '1200 chars', size: FONT_SMALL }] },
    { id: 'ok', style: 'service', x: 282, y: 520, w: 196, h: 52, lines: [{ t: 'Release to agent' }, { t: 'and log in receipt', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 'q', to: 'c1' },
    { from: 'c1', to: 'deny1', label: 'no' },
    { from: 'c1', to: 'c2', label: 'yes' },
    { from: 'c2', to: 'deny2', label: 'no' },
    { from: 'c2', to: 'c3', label: 'yes' },
    { from: 'c3', to: 'excl', label: 'hit' },
    { from: 'c3', to: 'c4', label: 'clean' },
    { from: 'c4', to: 'trim', label: 'over' },
    { from: 'c4', to: 'ok', label: 'ok' },
    { from: 'trim', to: 'ok', dashed: true },
  ],
}

// 图 6：网盘之上的 Agent 能力投影
export const fig6 = {
  id: 'fig6-projection', height: 372,
  groups: [
    { id: 'base', label: 'Drive primitives', style: 'store', x: 16, y: 8, w: 232, h: 348 },
    { id: 'must', label: 'What an agent needs', style: 'agent', x: 264, y: 8, w: 232, h: 348 },
    { id: 'bind', label: 'Binding in this system', style: 'service', x: 512, y: 8, w: 232, h: 348 },
  ],
  nodes: [
    { id: 'b1', style: 'store', x: 40, y: 52, w: 184, h: 48, lines: [{ t: 'file tree' }] },
    { id: 'b2', style: 'store', x: 40, y: 122, w: 184, h: 48, lines: [{ t: 'content bytes' }] },
    { id: 'b3', style: 'store', x: 40, y: 192, w: 184, h: 48, lines: [{ t: 'owner identity' }] },
    { id: 'b4', style: 'store', x: 40, y: 262, w: 184, h: 48, lines: [{ t: 'share links' }] },

    { id: 'm1', style: 'agent', x: 288, y: 52, w: 184, h: 48, lines: [{ t: 'scoped read' }] },
    { id: 'm2', style: 'agent', x: 288, y: 122, w: 184, h: 48, lines: [{ t: 'stream + resume' }] },
    { id: 'm3', style: 'agent', x: 288, y: 192, w: 184, h: 48, lines: [{ t: 'attribution' }] },
    { id: 'm4', style: 'agent', x: 288, y: 262, w: 184, h: 48, lines: [{ t: 'audience control' }] },

    { id: 'n1', style: 'service', x: 536, y: 52, w: 184, h: 48, lines: [{ t: 'ticked folders only' }] },
    { id: 'n2', style: 'service', x: 536, y: 122, w: 184, h: 48, lines: [{ t: 'chunked readers' }] },
    { id: 'n3', style: 'service', x: 536, y: 192, w: 184, h: 48, lines: [{ t: 'per-owner agent' }] },
    { id: 'n4', style: 'service', x: 536, y: 262, w: 184, h: 48, lines: [{ t: 'market + code' }] },
  ],
  edges: [
    { from: 'b1', to: 'm1' },
    { from: 'b2', to: 'm2' },
    { from: 'b3', to: 'm3' },
    { from: 'b4', to: 'm4' },
    { from: 'm1', to: 'n1' },
    { from: 'm2', to: 'n2' },
    { from: 'm3', to: 'n3' },
    { from: 'm4', to: 'n4' },
  ],
}

export const FIGURES = [
  { key: 'fig1-binding', data: fig1, page: 'Fig1-BindingSurfaces' },
  { key: 'fig2-bind', data: fig2, page: 'Fig2-AccessLifecycle' },
  { key: 'fig3-collab', data: fig3, page: 'Fig3-Collaboration' },
  { key: 'fig4-boundary', data: fig4, page: 'Fig4-Boundary' },
  { key: 'fig5-guard', data: fig5, page: 'Fig5-AccessControl' },
  { key: 'fig6-projection', data: fig6, page: 'Fig6-Projection' },
]
