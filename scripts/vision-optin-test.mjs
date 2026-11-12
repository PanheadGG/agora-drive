// 大模型开关 + 视觉判定 + 等级上限 冒烟测试
// 用法：后端已启动后 node scripts/vision-optin-test.mjs
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

async function runScan(token, bountyId, scopeIds, aiEnabled) {
  const started = await call('POST', '/agent/scan', token, { bountyId, scopeFileIds: scopeIds, aiEnabled })
  const taskId = started.body?.data?.taskId
  if (!taskId) return { started, detail: null }
  const deadline = Date.now() + 180000
  let progress = null
  while (Date.now() < deadline) {
    progress = (await call('GET', '/agent/tasks/' + taskId + '/progress', token)).body.data
    if (progress.done) break
    await sleep(300)
  }
  const detail = (await call('GET', '/agent/tasks/' + taskId, token)).body.data
  return { started, detail, progress }
}

const demo = (await call('POST', '/auth/login', null, { username: 'demo', password: 'demo1234' })).body.data
const token = demo.token
const folders = (await call('GET', '/agent/folders', token)).body.data
const tidy = folders.find((f) => f.name === '个人整理').id
const shots = folders.find((f) => f.name === '随手拍').id
const bounties = (await call('GET', '/bounty', token)).body.data
const bounty = bounties.find((b) => b.title.includes('R-01')) || bounties[0]
const hiking = bounties.find((b) => b.title.includes('R-02')) || bounties[1]

const status = (await call('GET', '/agent/llm-status', token)).body.data
check('llm-status exposes vision', !!status.vision, status)
console.log('      vision available: ' + status.vision.available + ' / llm: ' + status.llm.available)

// 1) 不开启大模型：等级上限必须生效
const off = await runScan(token, bounty.id, [tidy], false)
check('scan without ai finished', off.detail?.taskStatus === 2, off.started?.body?.msg)
check('task records aiEnabled=false', off.detail?.aiEnabled === false, off.detail?.aiEnabled)
check('no high match when ai off', !off.detail.parsedList.some((r) => r.matchLevel === 3), off.detail?.parsedList?.map((r) => r.fileName + ':' + r.matchLevel))
check('capped results flagged', off.detail.parsedList.some((r) => r.levelCapped === true), off.detail?.parsedList?.filter((r) => r.levelCapped).length)
check('notice explains cap', off.detail.notices.some((n) => n.includes('未开启大模型判定')), off.detail.notices)
check('llm not used when off', off.detail.llmUsed === false, off.detail.llmJudgedCount)
const cappedItem = off.detail.parsedList.find((r) => r.levelCapped)
check('capped label is 可能高匹配', cappedItem?.matchLabel === '可能高匹配', cappedItem?.matchLabel)

// 2) 开启大模型（若平台可用）：允许高匹配
if (status.llm.available) {
  const on = await runScan(token, bounty.id, [tidy], true)
  check('scan with ai finished', on.detail?.taskStatus === 2, on.started?.body?.msg)
  check('task records aiEnabled=true', on.detail?.aiEnabled === true, on.detail?.aiEnabled)
  check('llm actually used', on.detail.llmUsed === true, on.detail.llmJudgedCount)
  check('high match allowed when ai on', on.detail.parsedList.some((r) => r.matchLevel === 3 && r.levelCapped === false), on.detail?.parsedList?.map((r) => r.matchLevel))
  check('no cap notice when ai on', !on.detail.notices.some((n) => n.includes('未开启大模型判定')), on.detail.notices)
} else {
  console.log('SKIP  ai-on scan（当前没有可用模型平台）')
}

// 3) 图片：视觉判定（没有可用平台时应标记为待确认，且不给高匹配）
const img = await runScan(token, hiking.id, [shots], status.llm.available)
check('image scan finished', img.detail?.taskStatus === 2, img.started?.body?.msg)
const images = img.detail.parsedList.concat(img.detail.skipped).filter((r) => r.fileName.endsWith('.png'))
check('images present', images.length >= 3, images.length)
if (status.llm.available) {
  const withVision = images.filter((r) => r.visionScene || r.visionOk !== null)
  check('vision judged some images', withVision.length >= 1, images.map((r) => r.fileName + ':' + r.visionOk))
  const blocked = images.filter((r) => r.riskTag === '画面无关')
  console.log('      被识图排除的图片: ' + (blocked.map((r) => r.fileName).join(',') || '无'))
} else {
  check('no high match for images without ai', !images.some((r) => r.matchLevel === 3), images.map((r) => r.matchLevel))
}

// 4) 前端开关默认关闭
const fresh = await call('POST', '/agent/scan', token, { bountyId: bounty.id, scopeFileIds: [tidy] })
check('aiEnabled defaults to false', fresh.status === 200, fresh.body)
const freshDetail = await (async () => {
  const id = fresh.body.data.taskId
  for (let i = 0; i < 60; i++) {
    const p = (await call('GET', '/agent/tasks/' + id + '/progress', token)).body.data
    if (p.done) break
    await sleep(300)
  }
  return (await call('GET', '/agent/tasks/' + id, token)).body.data
})()
check('default scan is capped', freshDetail.aiEnabled === false && !freshDetail.parsedList.some((r) => r.matchLevel === 3), freshDetail.aiEnabled)

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
