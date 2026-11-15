// 六张图的唯一布局定义：drawio 与 SVG 两个导出器都从这里取数据，保证两者一致。
// 画布统一 720 宽；栅格 10px；字号统一（节点 12 / 边 11 / 注释 10）。

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

// 图 1：系统主闭环
export const fig1 = {
  id: 'fig1-loop', height: 384,
  groups: [
    { id: 'g1', label: 'Seller side — one agent per owner', style: 'agent', x: 16, y: 8, w: 688, h: 216 },
  ],
  nodes: [
    { id: 'own', style: 'store', x: 48, y: 56, w: 140, h: 60, lines: [{ t: 'Owner drive' }, { t: 'raw files', size: FONT_SMALL }] },
    { id: 'ag', style: 'agent', x: 236, y: 56, w: 152, h: 60, lines: [{ t: 'Seller Agent' }, { t: 'parse · aggregate', size: FONT_SMALL }] },
    { id: 'peer', style: 'agent', x: 444, y: 56, w: 152, h: 60, lines: [{ t: 'Peer Agent' }, { t: 'code + password', size: FONT_SMALL }] },
    { id: 'prod', style: 'service', x: 236, y: 146, w: 152, h: 58, lines: [{ t: 'Data product' }, { t: 'k-anon aggregates', size: FONT_SMALL }] },
    { id: 'joint', style: 'service', x: 444, y: 146, w: 152, h: 58, lines: [{ t: 'Joint product' }, { t: 'grows per member', size: FONT_SMALL }] },
    { id: 'mk', style: 'infra', x: 236, y: 250, w: 152, h: 54, lines: [{ t: 'Data market' }, { t: 'descriptions only', size: FONT_SMALL }] },
    { id: 'buyer', style: 'client', x: 236, y: 326, w: 152, h: 44, lines: [{ t: 'Buyer' }] },
  ],
  edges: [
    { from: 'own', to: 'ag' },
    { from: 'ag', to: 'prod' },
    { from: 'peer', to: 'joint' },
    { from: 'ag', to: 'joint' },
    { from: 'prod', to: 'mk' },
    { from: 'joint', to: 'mk' },
    { from: 'mk', to: 'buyer', label: 'demand / matched push' },
  ],
}

// 图 2：总体架构
export const fig2 = {
  id: 'fig2-arch', height: 436,
  groups: [
    { id: 'g1', label: 'Browser — Vue 3', style: 'client', x: 16, y: 8, w: 688, h: 64 },
    { id: 'g2', label: 'Service — Spring Boot / Kotlin :8080', style: 'service', x: 16, y: 84, w: 688, h: 64 },
    { id: 'g3', label: 'Agent core', style: 'agent', x: 16, y: 160, w: 688, h: 158 },
    { id: 'g4', label: 'Infrastructure', style: 'infra', x: 16, y: 330, w: 688, h: 128 },
  ],
  nodes: [
    { id: 'ui', style: 'client', x: 36, y: 32, w: 648, h: 30, lines: [{ t: 'drive · seller studio · market · collaboration', size: FONT_SMALL }] },
    { id: 'api', style: 'service', x: 36, y: 108, w: 648, h: 30, lines: [{ t: '76 REST endpoints · JWT guard · role check', size: FONT_SMALL }] },
    { id: 'bld', style: 'agent', x: 36, y: 186, w: 152, h: 48, lines: [{ t: 'Product builder' }] },
    { id: 'par', style: 'agent', x: 202, y: 186, w: 152, h: 48, lines: [{ t: 'Streaming parsers' }] },
    { id: 'pii', style: 'warn', x: 368, y: 186, w: 140, h: 48, lines: [{ t: 'PII scanner' }] },
    { id: 'col', style: 'agent', x: 522, y: 186, w: 162, h: 48, lines: [{ t: 'Collaboration' }] },
    { id: 'mkt', style: 'agent', x: 36, y: 258, w: 152, h: 44, lines: [{ t: 'Market engine' }] },
    { id: 'llm', style: 'agent', x: 202, y: 258, w: 152, h: 44, lines: [{ t: 'LLM router' }] },
    { id: 'pg', style: 'store', cyl: true, x: 60, y: 352, w: 160, h: 66, lines: [{ t: 'PostgreSQL 18' }, { t: '19 tables', size: FONT_SMALL }] },
    { id: 'fs', style: 'store', cyl: true, x: 236, y: 352, w: 150, h: 66, lines: [{ t: 'File store' }, { t: 'sha256', size: FONT_SMALL }] },
    { id: 'vault', style: 'infra', x: 410, y: 362, w: 132, h: 46, lines: [{ t: 'Key vault' }, { t: 'AES-256-GCM', size: FONT_SMALL }] },
    { id: 'ext', style: 'start', x: 570, y: 362, w: 114, h: 46, lines: [{ t: 'DeepSeek' }, { t: 'optional', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 'ui', to: 'api' },
    { from: 'api', to: 'bld', side: 'left' },
    { from: 'api', to: 'col', side: 'right' },
    { from: 'bld', to: 'par' },
    { from: 'par', to: 'pii' },
    { from: 'bld', to: 'llm' },
    { from: 'mkt', to: 'pg' },
    { from: 'llm', to: 'fs' },
    { from: 'col', to: 'vault', side: 'right' },
    { from: 'col', to: 'ext', dashed: true, side: 'right' },
  ],
}

// 图 3：数据模型
export const fig3 = {
  id: 'fig3-er', height: 320,
  groups: [],
  nodes: [
    { id: 'user', style: 'start', x: 20, y: 16, w: 176, h: 64, lines: [{ t: 'user', bold: true }, { t: 'id · username · role', size: FONT_SMALL }] },
    { id: 'user_file', style: 'service', x: 20, y: 132, w: 176, h: 64, lines: [{ t: 'user_file', bold: true }, { t: 'id · user_id · meta_id', size: FONT_SMALL }] },
    { id: 'file_meta', style: 'store', x: 20, y: 248, w: 176, h: 64, lines: [{ t: 'file_meta', bold: true }, { t: 'id · sha256 · size', size: FONT_SMALL }] },
    { id: 'data_product', style: 'agent', x: 236, y: 16, w: 196, h: 64, lines: [{ t: 'data_product', bold: true }, { t: 'id · owner_id · template', size: FONT_SMALL }] },
    { id: 'product_listing', style: 'agent', x: 236, y: 132, w: 196, h: 64, lines: [{ t: 'product_listing', bold: true }, { t: 'id · product_id · price', size: FONT_SMALL }] },
    { id: 'market_demand', style: 'client', x: 236, y: 248, w: 196, h: 64, lines: [{ t: 'market_demand', bold: true }, { t: 'id · buyer_id · requirement', size: FONT_SMALL }] },
    { id: 'market_match', style: 'client', x: 472, y: 190, w: 196, h: 64, lines: [{ t: 'market_match', bold: true }, { t: 'id · demand_id · listing_id', size: FONT_SMALL }] },
    { id: 'collab_session', style: 'infra', x: 472, y: 16, w: 196, h: 64, lines: [{ t: 'collab_session', bold: true }, { t: 'id · code · owner_id', size: FONT_SMALL }] },
    { id: 'collab_member', style: 'infra', x: 472, y: 104, w: 196, h: 64, lines: [{ t: 'collab_member', bold: true }, { t: 'id · session_id · user_id', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 'user', to: 'user_file', label: '1:n' },
    { from: 'file_meta', to: 'user_file', label: '1:n' },
    { from: 'user', to: 'data_product', label: '1:n' },
    { from: 'data_product', to: 'product_listing', label: '1:n' },
    { from: 'user', to: 'market_demand', label: '1:n', way: 'gutter' },
    { from: 'product_listing', to: 'market_match', label: '1:n' },
    { from: 'market_demand', to: 'market_match', label: '1:n' },
    { from: 'collab_session', to: 'collab_member', label: '1:n' },
  ],
}

// 图 4：加工流水线
export const fig4 = {
  id: 'fig4-pipeline', height: 652,
  groups: [],
  nodes: [
    { id: 's0', style: 'start', x: 250, y: 8, w: 220, h: 44, lines: [{ t: 'One-sentence request' }] },
    { id: 's1', style: 'agent', x: 236, y: 82, w: 248, h: 54, lines: [{ t: 'Agent selects template' }, { t: 'rule match, then LLM check', size: FONT_SMALL }] },
    { id: 's2', style: 'client', x: 236, y: 166, w: 248, h: 54, lines: [{ t: 'Owner ticks folders' }, { t: 'only these are opened', size: FONT_SMALL }] },
    { id: 's3', style: 'agent', x: 236, y: 250, w: 248, h: 54, lines: [{ t: 'Streaming parse' }, { t: 'xlsx · docx · pdf · OCR · zip', size: FONT_SMALL }] },
    { id: 's4', style: 'decide', dec: true, x: 262, y: 330, w: 196, h: 84, lines: [{ t: 'Pre-flight check' }] },
    { id: 'x', style: 'warn', x: 536, y: 344, w: 148, h: 56, lines: [{ t: 'Excluded' }, { t: 'reason in receipt', size: FONT_SMALL }] },
    { id: 's5', style: 'agent', x: 236, y: 438, w: 248, h: 48, lines: [{ t: 'Extract rows by template' }] },
    { id: 's6', style: 'service', x: 228, y: 510, w: 264, h: 54, lines: [{ t: 'Aggregate by dimension' }, { t: 'k-anonymity drops small groups', size: FONT_SMALL }] },
    { id: 's7', style: 'infra', x: 228, y: 588, w: 264, h: 54, lines: [{ t: 'Build receipt' }, { t: 'source sha256 + params + model', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 's0', to: 's1' },
    { from: 's1', to: 's2' },
    { from: 's2', to: 's3' },
    { from: 's3', to: 's4' },
    { from: 's4', to: 'x', label: 'sensitive' },
    { from: 's4', to: 's5', label: 'clean' },
    { from: 's5', to: 's6' },
    { from: 's6', to: 's7' },
    { from: 'x', to: 's7', dashed: true },
  ],
}

// 图 5：协同（泳道活动图）
export const fig5 = {
  id: 'fig5-collab', height: 452,
  groups: [
    { id: 'l1', label: 'Initiator (Agent A)', style: 'agent', x: 16, y: 8, w: 224, h: 440 },
    { id: 'l2', label: 'Collaboration hub', style: 'service', x: 248, y: 8, w: 224, h: 440 },
    { id: 'l3', label: 'Member (Agent B)', style: 'client', x: 480, y: 8, w: 224, h: 440 },
  ],
  nodes: [
    // t1：发起方描述需求，中心建会话
    { id: 'a1', style: 'agent',   x: 44,  y: 44,  w: 168, h: 44, lines: [{ t: 'describe joint product' }] },
    { id: 'h1', style: 'service', x: 276, y: 44,  w: 168, h: 44, lines: [{ t: 'create session' }] },
    // t2：中心发号，成员收号
    { id: 'h2', style: 'service', x: 276, y: 112, w: 168, h: 44, lines: [{ t: 'issue code + password' }] },
    { id: 'b1', style: 'client',  x: 508, y: 112, w: 168, h: 44, lines: [{ t: 'receive code' }] },
    // t3：成员预览邀请
    { id: 'b2', style: 'client',  x: 508, y: 180, w: 168, h: 44, lines: [{ t: 'preview invite' }] },
    // t4：成员勾选文件夹加入 → 中心校验
    { id: 'b3', style: 'client',  x: 508, y: 248, w: 168, h: 44, lines: [{ t: 'join + tick folder' }] },
    { id: 'h3', style: 'service', x: 276, y: 248, w: 168, h: 44, lines: [{ t: 'verify code' }] },
    // t5：双方各自本地聚合，统计上报中心
    { id: 'a2', style: 'agent',   x: 44,  y: 316, w: 168, h: 44, lines: [{ t: 'local aggregation' }] },
    { id: 'b4', style: 'client',  x: 508, y: 316, w: 168, h: 44, lines: [{ t: 'local aggregation' }] },
    { id: 'h4', style: 'service', x: 276, y: 316, w: 168, h: 44, lines: [{ t: 'union of group stats' }] },
    // t6：产品增大 → 发起方上架
    { id: 'h5', style: 'service', x: 276, y: 384, w: 168, h: 44, lines: [{ t: 'product grows' }] },
    { id: 'a3', style: 'agent',   x: 44,  y: 384, w: 168, h: 44, lines: [{ t: 'publish' }] },
  ],
  edges: [
    { from: 'a1', to: 'h1' },
    { from: 'h1', to: 'h2' },
    { from: 'h2', to: 'b1', label: 'code (once)', dashed: true },
    { from: 'b1', to: 'b2' },
    { from: 'b2', to: 'b3' },
    { from: 'b3', to: 'h3' },
    { from: 'a2', to: 'h4' },
    { from: 'b4', to: 'h4', dashed: true },
    { from: 'h3', to: 'a2', dashed: true, way: 'hub-left' },
    { from: 'h4', to: 'h5' },
    { from: 'h5', to: 'a3' },
  ],
}

// 图 6：协同状态机
export const fig6 = {
  id: 'fig6-state', height: 274,
  groups: [],
  nodes: [
    { id: 'st0', style: 'agent',   x: 16,  y: 40,  w: 176, h: 62, lines: [{ t: 'Created' }, { t: 'code + password', size: FONT_SMALL }] },
    { id: 'st1', style: 'service', x: 292, y: 40,  w: 176, h: 62, lines: [{ t: 'Open' }, { t: 'members may join', size: FONT_SMALL }] },
    { id: 'st2', style: 'infra',   x: 568, y: 40,  w: 176, h: 62, lines: [{ t: 'Published' }, { t: 'listed in market', size: FONT_SMALL }] },
    { id: 'st3', style: 'start',   x: 292, y: 200, w: 176, h: 62, lines: [{ t: 'Closed' }, { t: 'no longer accepting', size: FONT_SMALL }] },
  ],
  edges: [
    { from: 'st0', to: 'st1', label: 'describe' },
    { from: 'st1', to: 'st2', label: 'publish' },
    { from: 'st1', to: 'st3', label: 'close' },
  ],
}

export const FIGURES = [
  { key: 'fig1-loop', data: fig1, page: 'Fig1-SystemLoop' },
  { key: 'fig2-arch', data: fig2, page: 'Fig2-Architecture' },
  { key: 'fig3-er', data: fig3, page: 'Fig3-DataModel' },
  { key: 'fig4-pipeline', data: fig4, page: 'Fig4-Pipeline' },
  { key: 'fig5-collab', data: fig5, page: 'Fig5-Collaboration' },
  { key: 'fig6-state', data: fig6, page: 'Fig6-StateMachine' },
]
