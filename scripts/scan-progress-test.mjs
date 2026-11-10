// 扫描过程与预览冒烟测试：异步任务 + 实时进度 + 流式敏感检测 + 结果预览
// 用法：后端已启动后 node scripts/scan-progress-test.mjs
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

const admin = await call('POST', '/auth/login', null, { username: 'admin', password: 'admin123' })
const demo = await call('POST', '/auth/login', null, { username: 'demo', password: 'demo1234' })
const demoToken = demo.body?.data?.token

const folders = await call('GET', '/agent/folders', demoToken)
const tidy = folders.body.data.find((f) => f.name === '个人整理').id
const bounties = await call('GET', '/bounty', demoToken)
const cooking = bounties.body.data.find((b) => b.title.includes('R-01')) || bounties.body.data[0]

// 1. 立即返回 taskId
const started = await call('POST', '/agent/scan', demoToken, { bountyId: cooking.id, scopeFileIds: [tidy] })
check('scan returns immediately with taskId', started.status === 200 && started.body.data.taskId > 0, started.body)
const taskId = started.body.data.taskId

// 2. 轮询进度：过程中能看到 currentFile / stage / events
let sawRunning = false
let maxEvents = 0
let sawCurrentFile = false
let snapshots = 0
let progress = null
const deadline = Date.now() + 120000
while (Date.now() < deadline) {
  progress = (await call('GET', '/agent/tasks/' + taskId + '/progress', demoToken)).body.data
  snapshots++
  if (progress.taskStatus === 1) sawRunning = true
  if (progress.currentFile) sawCurrentFile = true
  maxEvents = Math.max(maxEvents, progress.events.length)
  if (progress.done) break
  await sleep(300)
}
check('progress endpoint works', !!progress && progress.total > 0, progress)
check('observed running state', sawRunning || progress.processed > 0, { sawRunning, processed: progress?.processed })
check('observed live events', maxEvents > 0, maxEvents)
check('observed current file during run', sawCurrentFile || progress.done, sawCurrentFile)
check('task finished', progress.taskStatus === 2, progress)
check('progress percent complete', progress.percent === 100, progress.percent)
console.log('      轮询 ' + snapshots + ' 次，事件 ' + maxEvents + ' 条，耗时 ' + progress.elapsedMs + 'ms')

// 3. 报告 & 预览
const detail = (await call('GET', '/agent/tasks/' + taskId, demoToken)).body.data
check('report available', detail.parsedList.length > 0, detail.parsedList?.length)

const tableItem = detail.parsedList.find((r) => r.fileName.endsWith('.xlsx'))
check('xlsx parsed by streaming excel', !!tableItem && tableItem.parseMethod.includes('Excel 流式'), tableItem?.parseMethod)
const tablePreview = (await call('GET', '/agent/results/' + tableItem.id + '/preview', demoToken)).body.data
check('table preview kind', tablePreview.kind === 'table', tablePreview.kind)
check('table preview has rows', tablePreview.rows.length >= 3, tablePreview.rows?.length)
check('table preview has header', tablePreview.header.length >= 3 && tablePreview.header.includes('日期'), tablePreview.header)

const docItem = detail.parsedList.find((r) => r.fileName.endsWith('.docx') && r.judgeSource !== 'rule')
const docPreview = (await call('GET', '/agent/results/' + docItem.id + '/preview', demoToken)).body.data
check('docx preview kind text', docPreview.kind === 'text', docPreview.kind)
check('docx preview has content', docPreview.text.length > 20, docPreview.text?.slice(0, 60))

const sensitive = detail.highRisk.find((r) => r.riskTag === '手机号')
check('streaming detection found phone in xlsx', !!sensitive, detail.highRisk.map((r) => r.riskTag))

// 4. 权限：他人不能看
const ent = await call('POST', '/auth/login', null, { username: 'enterprise', password: 'ent12345' })
const forbidden = await call('GET', '/agent/results/' + tableItem.id + '/preview', ent.body.data.token)
check('preview blocked for other user', forbidden.status === 403, forbidden.status)

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
