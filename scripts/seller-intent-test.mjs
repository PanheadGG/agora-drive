// 普通用户视角：一句话描述 → Agent 选模板 → 整理出产品
const BASE = 'http://127.0.0.1:8080/api'
let failed = 0
const check = (name, ok, detail) => {
  if (ok) console.log('PASS  ' + name)
  else { failed++; console.log('FAIL  ' + name + '  -> ' + JSON.stringify(detail).slice(0, 300)) }
}
async function call(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch {}
  return { status: res.status, body: json }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const token = (await call('POST', '/auth/login', null, { username: 'seller_a', password: 'sell1234' })).body.data.token

// 1. 一句话 → 模板识别
const cases = [
  { text: '把我记录的通勤时间整理成按线路的平均耗时', expect: 'commute_stats' },
  { text: '我想把家里的菜谱文档整理成能查的菜谱库', expect: 'recipe_library' },
  { text: '整理一下猫咪的喂养记录，体重和驱虫那些', expect: 'pet_feeding' },
  { text: '把读书笔记整理成按书统计', expect: 'reading_notes' },
]
for (const item of cases) {
  const r = await call('POST', '/seller/intent', token, { description: item.text })
  const intent = r.body.data
  check('intent: ' + item.text.slice(0, 12), intent.templateKey === item.expect, {
    got: intent.templateKey, expect: item.expect, reason: intent.reason,
  })
}

const understood = (await call('POST', '/seller/intent', token, { description: cases[0].text })).body.data
check('intent exposes plain fields', understood.fields.length >= 3, understood.fields)
check('intent has confidence', understood.confidence > 0, understood.confidence)
check('intent has reason', understood.reason.length > 5, understood.reason)

// 2. 模糊描述：仍需给出确定模板（不能报错）
const vague = await call('POST', '/seller/intent', token, { description: '我想卖点数据' })
check('vague description still resolves', vague.status === 200 && !!vague.body.data.templateKey, vague.body)

// 3. 用描述直接建产品（不传 templateKey）
const folders = (await call('GET', '/seller/folders', token)).body.data
const created = await call('POST', '/seller/products', token, {
  description: '把我记录的通勤时间整理成按线路的平均耗时',
  title: '',
  scopeFileIds: folders.map((f) => f.id),
})
check('create from description', created.status === 200 && created.body.data.productId > 0, created.body)
const productId = created.body.data.productId
let done = false
for (let i = 0; i < 120; i++) {
  const p = (await call('GET', '/seller/build/' + created.body.data.taskId + '/progress', token)).body.data
  if (p.done) { done = true; break }
  await sleep(300)
}
check('build finished', done, 'timeout')
const detail = (await call('GET', '/seller/products/' + productId, token)).body.data
check('picked commute template automatically', detail.product.templateKey === 'commute_stats', detail.product.templateKey)
check('ai enabled by default', detail.product.aiEnabled === 1, detail.product.aiEnabled)
check('product has groups', JSON.parse(detail.product.qualityJson).groups > 0, detail.product.qualityJson)

// 4. 不传 aiEnabled 时默认开启（不再是可选开关）
const noFlag = await call('POST', '/seller/products', token, {
  description: '整理通勤记录', scopeFileIds: folders.map((f) => f.id),
})
check('omitted aiEnabled defaults on', noFlag.status === 200, noFlag.body?.msg)

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
