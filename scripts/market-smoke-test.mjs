// 数据市场全链路测试：卖方 Agent 加工 → 上架 → 买方需求 → 主动推送 → 交付 → 协同联合产品
const BASE = process.env.ACD_BASE || 'http://127.0.0.1:8080/api'
let failed = 0
const check = (name, ok, detail) => {
  if (ok) console.log('PASS  ' + name)
  else { failed++; console.log('FAIL  ' + name + '  -> ' + JSON.stringify(detail).slice(0, 400)) }
}
async function call(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* ignore */ }
  return { status: res.status, body: json }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const login = async (u, p) => (await call('POST', '/auth/login', null, { username: u, password: p })).body.data

const a = await login('seller_a', 'sell1234')
const b = await login('seller_b', 'sell1234')
const buyer = await login('buyer', 'buy12345')
check('seller_a login', !!a?.token, a)
check('seller_b login', !!b?.token, b)
check('buyer login', !!buyer?.token, buyer)

// 1. 模板
const tpl = await call('GET', '/seller/templates', a.token)
check('templates available', tpl.status === 200 && tpl.body.data.length >= 3, tpl.body?.data?.length)
const commuteTpl = tpl.body.data.find((t) => t.key === 'commute_stats')
check('commute template present', !!commuteTpl, tpl.body.data.map((t) => t.key))

// 2. 种子产品与上架
const products = (await call('GET', '/seller/products', a.token)).body.data
check('seller_a has seeded product', products.length >= 1, products.length)
// 取单方产品（联合产品没有单方原料哈希，只有贡献者摘要，这是设计如此）
const seeded = products.find((p) => p.sourceKind === 'solo') || products[0]
check('product has groups', JSON.parse(seeded.qualityJson).groups > 0, seeded.qualityJson)
const receipt = JSON.parse(seeded.buildReceipt)
check('solo product has receipt digest', !!receipt.digest, seeded.buildReceipt?.slice(0, 120))
check('receipt records k-anonymity', receipt.kAnonymity >= 2, receipt.kAnonymity)
check('product pii clean', seeded.piiStatus === 'clean', seeded.piiStatus)

const listings = (await call('GET', '/market/listings', buyer.token)).body.data
check('market has listings', listings.length >= 2, listings.length)
check('listing hides raw file names', !JSON.stringify(listings).includes('通勤记录.xlsx'), JSON.stringify(listings).slice(0, 200))

// 3. 新加工一个产品（验证 Agent 全链路）
const folders = (await call('GET', '/seller/folders', a.token)).body.data
const created = await call('POST', '/seller/products', a.token, {
  templateKey: 'commute_stats',
  title: 'E2E 通勤产品',
  scopeFileIds: folders.map((f) => f.id),
  aiEnabled: false,
})
check('create product', created.status === 200 && created.body.data.productId > 0, created.body)
const newProductId = created.body.data.productId
let done = false
for (let i = 0; i < 120; i++) {
  const p = (await call('GET', '/seller/build/' + created.body.data.taskId + '/progress', a.token)).body.data
  if (p.done) { done = true; break }
  await sleep(300)
}
check('build finished', done, 'timeout')
const detail = (await call('GET', '/seller/products/' + newProductId, a.token)).body.data
check('built product has groups', JSON.parse(detail.product.qualityJson).groups > 0, detail.product.qualityJson)
check('build items recorded', detail.items.length >= 1, detail.items?.length)
check('items carry sha256', detail.items.some((i) => i.fileSha256), detail.items?.map((i) => i.fileSha256))

// 4. 上架/下架/失效
const pub = await call('POST', '/seller/products/' + newProductId + '/publish', a.token, {
  title: 'E2E 通勤产品', summary: '端到端测试用通勤聚合', priceCents: 3000, licenseType: 'internal',
})
check('publish product', pub.status === 200 && pub.body.data.id > 0, pub.body)
const listingId = pub.body.data.id

// 5. 买方需求 + 主动推送
const demand = await call('POST', '/market/demands', buyer.token, {
  title: '需要早晚高峰通勤耗时',
  rawDesc: '想了解地铁和公交线路的通勤平均耗时，用于出行规划，近一年数据最好。',
  budgetCents: 15000,
})
check('create demand', demand.status === 200, demand.body)
const pushes = (await call('GET', '/market/pushes', buyer.token)).body.data
check('pushes received', pushes.length >= 1, pushes.length)
check('push has reasons', JSON.parse(pushes[0].reasonsJson).length >= 1, pushes[0].reasonsJson)
check('push excludes self listings', pushes.every((p) => p.sellerName !== 'buyer'), pushes.map((p) => p.sellerName))
const push = pushes.find((p) => p.state === 0) || pushes[0]

// 6. 交付（最小化）
const read = await call('POST', '/market/pushes/' + push.id + '/read', buyer.token)
check('mark push read', read.body.data.state === 1, read.body)
const delivery = await call('POST', '/market/pushes/' + push.id + '/delivery?note=' + encodeURIComponent('希望拿到聚合数据'), buyer.token)
check('request delivery', delivery.status === 200 && delivery.body.data.id > 0, delivery.body)
const deliveryId = delivery.body.data.id
const sellerDeliveries = (await call('GET', '/seller/deliveries', push.sellerName === 'seller_a' ? a.token : b.token)).body.data
check('seller sees delivery', sellerDeliveries.some((d) => d.id === deliveryId), sellerDeliveries.length)
const sellerToken = push.sellerName === 'seller_a' ? a.token : b.token
const accepted = await call('POST', '/seller/deliveries/' + deliveryId + '/accept', sellerToken)
check('seller accepts delivery', accepted.body.data.status === 1, accepted.body)
const completed = await call('POST', '/seller/deliveries/' + deliveryId + '/complete', sellerToken)
check('seller completes delivery', completed.body.data.status === 2, completed.body)

// 7. 下架使推送失效
const delist = await call('POST', '/seller/products/' + newProductId + '/delist', a.token)
check('delist product', delist.status === 200, delist.body)
const listingsAfter = (await call('GET', '/market/listings', buyer.token)).body.data
check('delisted listing hidden', !listingsAfter.some((l) => l.id === listingId), listingsAfter.map((l) => l.id))

// 8. Agent 协同联合产品
const invite = await call('POST', '/market/collab/invitations', a.token, {
  partnerId: b.user.id, templateKey: 'commute_stats', title: '联合通勤统计', message: '一起做个覆盖两城的通勤产品', kThreshold: 3,
})
check('invite created', invite.status === 200 && invite.body.data.id > 0, invite.body)
const sessionId = invite.body.data.id
const accepted2 = await call('POST', '/market/collab/' + sessionId + '/accept', b.token)
check('partner accepts', accepted2.body.data.state === 1, accepted2.body)

const foldersA = (await call('GET', '/seller/folders', a.token)).body.data
const foldersB = (await call('GET', '/seller/folders', b.token)).body.data
const contribA = await call('POST', '/market/collab/' + sessionId + '/contribute', a.token, {
  scopeFileIds: foldersA.map((f) => f.id), aiEnabled: false, approve: true,
})
check('A contributes', contribA.status === 200, contribA.body?.msg)
const contribB = await call('POST', '/market/collab/' + sessionId + '/contribute', b.token, {
  scopeFileIds: foldersB.map((f) => f.id), aiEnabled: false, approve: true,
})
check('B contributes', contribB.status === 200, contribB.body?.msg)
check('contributions stored', contribB.body.data.contributions.length === 2, contribB.body.data.contributions?.length)
check('merged preview has common groups', contribB.body.data.mergedPreview.length >= 1, contribB.body.data.mergedPreview)

const merged = await call('POST', '/market/collab/' + sessionId + '/merge', a.token)
check('merge joint product', merged.status === 200 && merged.body.data.session.state === 3, merged.body)
const jointProductId = merged.body.data.session.productId
check('joint product bound', jointProductId > 0, jointProductId)
const jointDetail = (await call('GET', '/seller/products/' + jointProductId, a.token)).body.data
check('joint product marked', jointDetail.product.sourceKind === 'joint', jointDetail.product.sourceKind)
check('joint product has receipt contributors', (JSON.parse(jointDetail.product.buildReceipt).contributors || []).length === 2, jointDetail.product.buildReceipt?.slice(0, 200))

// 9. 权限
const forbidden = await call('GET', '/seller/products/' + jointProductId, b.token)
check('non-owner blocked from product', forbidden.status === 403, forbidden.status)
const noAuth = await call('GET', '/market/listings', null)
check('market requires auth', noAuth.status === 401, noAuth.status)

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
