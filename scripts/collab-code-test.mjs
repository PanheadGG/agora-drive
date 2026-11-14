// 多 Agent 协同（协同号 + 协同密码）全链路测试
const BASE = 'http://127.0.0.1:8080/api'
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
  try { json = text ? JSON.parse(text) : null } catch {}
  return { status: res.status, body: json }
}
const login = async (u, p) => (await call('POST', '/auth/login', null, { username: u, password: p })).body.data

const a = await login('seller_a', 'sell1234')
const b = await login('seller_b', 'sell1234')
check('seller_a login', !!a?.token, a)
check('seller_b login', !!b?.token, b)

const foldersA = (await call('GET', '/seller/folders', a.token)).body.data
const foldersB = (await call('GET', '/seller/folders', b.token)).body.data
const scopeA = foldersA.map((f) => f.id)
const scopeB = foldersB.map((f) => f.id)

// 1. A 发起协同：拿到协同号 + 协同密码
const created = await call('POST', '/market/collab/create', a.token, {
  description: '把两边的通勤记录联合起来，看不同线路的平均耗时',
  title: 'E2E 联合通勤协同',
  scopeFileIds: scopeA,
})
check('create collab', created.status === 200, created.body)
const res = created.body.data
const sessionId = res.sessionId
const code = res.collabCode
const password = res.collabPassword
check('collab code returned', /^[A-Z0-9]{8}$/.test(code), code)
check('collab password returned', /^[A-Z0-9]{6}$/.test(password), password)
check('owner is member', res.detail.members.length === 1, res.detail.members?.length)
check('owner contributed', res.detail.totalGroups > 0, res.detail.totalGroups)
const groupsAfterOwner = res.detail.totalGroups

// 2. B 凭协同号 + 密码查看邀请
const peek = await call('POST', '/market/collab/peek', b.token, { collabCode: code, collabPassword: password })
check('peek by code', peek.status === 200, peek.body)
check('peek shows template', !!peek.body.data.templateLabel, peek.body.data?.templateLabel)
check('peek can join', peek.body.data.canJoin === true, peek.body.data?.canJoin)
check('peek shows owner', !!peek.body.data.ownerName, peek.body.data?.ownerName)
check('peek not yet joined', peek.body.data.alreadyJoined === false, peek.body.data?.alreadyJoined)

// 3. 错误密码被拒
const badPwd = await call('POST', '/market/collab/peek', b.token, { collabCode: code, collabPassword: 'WRONG1' })
check('wrong password rejected', badPwd.status === 403, badPwd.status)
const badCode = await call('POST', '/market/collab/peek', b.token, { collabCode: 'ZZZZZZZZ', collabPassword: password })
check('unknown code rejected', badCode.status === 404, badCode.status)

// 4. B 加入 → 产品动态增大
const join = await call('POST', '/market/collab/join', b.token, {
  collabCode: code, collabPassword: password, scopeFileIds: scopeB,
})
check('join collab', join.status === 200, join.body)
check('members grew to 2', join.body.data.members.length === 2, join.body.data?.members?.length)
check('product grew', join.body.data.totalGroups > groupsAfterOwner, {
  before: groupsAfterOwner, after: join.body.data.totalGroups,
})
check('aggregate merged', join.body.data.aggregate.length > 0, join.body.data?.aggregate?.length)
check('aggregate has source count', join.body.data.aggregate.every((r) => r.sources), join.body.data.aggregate?.[0])

// 5. 重复加入不会重复计数
const rejoin = await call('POST', '/market/collab/join', b.token, {
  collabCode: code, collabPassword: password, scopeFileIds: scopeB,
})
check('rejoin keeps 2 members', rejoin.body.data.members.length === 2, rejoin.body.data?.members?.length)

// 6. 成员追加文件夹
const addFiles = await call('POST', '/market/collab/' + sessionId + '/files', b.token, { scopeFileIds: scopeB })
check('member can add files', addFiles.status === 200, addFiles.body?.msg)

// 7. 非发起人不能上架
const notOwner = await call('POST', '/market/collab/' + sessionId + '/publish', b.token, { priceCents: 3000 })
check('non-owner cannot publish', notOwner.status === 403, notOwner.status)

// 8. 发起人上架
const publish = await call('POST', '/market/collab/' + sessionId + '/publish', a.token, {
  title: 'E2E 联合通勤协同', summary: '两位成员协同生成', priceCents: 3000, licenseType: 'internal',
})
check('owner publishes', publish.status === 200 && publish.body.data.productId > 0, publish.body)
const productId = publish.body.data.productId

const listing = (await call('GET', '/market/listings', b.token)).body.data.find((l) => l.productId === productId)
check('product listed in market', !!listing, listing)
const detail = (await call('GET', '/seller/products/' + productId, a.token)).body.data
check('product marked joint', detail.product.sourceKind === 'joint', detail.product.sourceKind)
check('receipt lists contributors', (JSON.parse(detail.product.buildReceipt).contributors || []).length === 2, detail.product.buildReceipt?.slice(0, 160))
check('market hides raw file names', !JSON.stringify(listing).includes('通勤记录.xlsx'), 'leak')

// 9. 已上架后不可再加入
const joinAfterPublish = await call('POST', '/market/collab/join', a.token, {
  collabCode: code, collabPassword: password, scopeFileIds: scopeA,
})
check('cannot join after publish', joinAfterPublish.status === 400, joinAfterPublish.status)

// 10. 非成员看不到详情
const outsider = await login('buyer', 'buy12345')
const forbidden = await call('GET', '/market/collab/' + sessionId, outsider.token)
check('outsider blocked', forbidden.status === 403, forbidden.status)

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
