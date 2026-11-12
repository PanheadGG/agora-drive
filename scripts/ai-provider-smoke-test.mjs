// AI 模型管理冒烟测试（自包含）：多平台增删改查、Key 加密存储、连通性测试、故障自动切换
// 用法：后端已启动后执行 node scripts/ai-provider-smoke-test.mjs
// 说明：测试会自己在 9098 端口拉起 scripts/mock-deepseek.mjs 作为「自定义平台」，跑完自动关闭。
import { spawn } from 'node:child_process'

const BASE = process.env.ACD_BASE || 'http://127.0.0.1:8080/api'
const MOCK_PORT = 9098
let failed = 0

const check = (name, ok, detail) => {
  if (ok) console.log('PASS  ' + name)
  else {
    failed += 1
    console.log('FAIL  ' + name + '  -> ' + JSON.stringify(detail).slice(0, 400))
  }
}

async function call(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* ignore */ }
  return { status: res.status, body: json, text }
}

// 1. 拉起 mock 平台
const mock = spawn(process.execPath, [new URL('./mock-deepseek.mjs', import.meta.url).pathname.replace(/^\//, ''), String(MOCK_PORT)], {
  stdio: ['ignore', 'pipe', 'pipe'],
})
await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('mock 启动超时')), 10000)
  mock.stdout.on('data', (chunk) => {
    if (String(chunk).includes('listening')) {
      clearTimeout(timer)
      resolve()
    }
  })
  mock.on('error', reject)
})
console.log('mock 平台已启动端口 ' + MOCK_PORT)

const cleanupIds = []
try {
  const admin = await call('POST', '/auth/login', null, { username: 'admin', password: 'admin123' })
  const adminToken = admin.body?.data?.token
  const demo = await call('POST', '/auth/login', null, { username: 'demo', password: 'demo1234' })
  const demoToken = demo.body?.data?.token
  check('admin login', !!adminToken, admin.body)
  check('demo login', !!demoToken, demo.body)

  // 2. 列表 / 加密状态
  const list = await call('GET', '/admin/ai-providers', adminToken)
  check('provider list', list.status === 200 && Array.isArray(list.body.data), list.body)
  check('key never returned in plaintext', !/sk-[0-9a-f]{20,}/.test(JSON.stringify(list.body)), '列表响应中出现疑似明文 Key')

  const status = await call('GET', '/admin/ai-providers/status', adminToken)
  check('llm status', status.status === 200 && status.body.data.providerCount >= 1, status.body)
  check('crypto algo is aes-gcm-256', status.body.data.crypto.algo === 'aes-gcm-256', status.body.data.crypto)
  check('master key source reported', ['env', 'file', 'generated'].includes(status.body.data.crypto.keySource), status.body.data.crypto)

  // 3. 新增自定义平台（指向 mock）
  const mockProvider = await call('POST', '/admin/ai-providers', adminToken, {
    provider: 'custom',
    name: 'Mock 平台 A',
    baseUrl: 'http://127.0.0.1:' + MOCK_PORT,
    model: 'mock-chat',
    apiKey: 'sk-mock-provider-a-0001',
    priority: 5,
    status: 1,
    timeoutSeconds: 15,
  })
  check('create custom provider', mockProvider.status === 200 && mockProvider.body.data.id > 0, mockProvider.body)
  const mockId = mockProvider.body?.data?.id ?? -1
  if (mockId > 0) cleanupIds.push(mockId)
  check('created key masked', /\*{4}/.test(mockProvider.body.data.maskedKey), mockProvider.body.data?.maskedKey)
  check('created key not echoed', !JSON.stringify(mockProvider.body).includes('sk-mock-provider-a-0001'), mockProvider.body)

  // 4. 新增一个不可达平台并排到最前，用于验证自动切换
  const brokenProvider = await call('POST', '/admin/ai-providers', adminToken, {
    provider: 'custom',
    name: '不可达平台',
    baseUrl: 'http://127.0.0.1:1/v1',
    model: 'broken-model',
    apiKey: 'sk-broken-platform-0002',
    priority: 1,
    status: 1,
    timeoutSeconds: 5,
  })
  check('create unreachable provider', brokenProvider.status === 200, brokenProvider.body)
  const brokenId = brokenProvider.body?.data?.id ?? -1
  if (brokenId > 0) cleanupIds.push(brokenId)

  // 5. 连通性测试
  const mockTest = await call('POST', '/admin/ai-providers/' + mockId + '/test', adminToken)
  check('mock provider test ok', mockTest.status === 200 && mockTest.body.data.ok === true, mockTest.body)
  const brokenTest = await call('POST', '/admin/ai-providers/' + brokenId + '/test', adminToken)
  check('unreachable provider test fails', brokenTest.status === 200 && brokenTest.body.data.ok === false, brokenTest.body)

  // 6. 更新（改优先级 + 轮换 Key）
  const rotated = await call('PUT', '/admin/ai-providers/' + mockId, adminToken, {
    provider: 'custom',
    name: 'Mock 平台 A（已轮换）',
    baseUrl: 'http://127.0.0.1:' + MOCK_PORT,
    model: 'mock-chat',
    apiKey: 'sk-mock-provider-a-rotated-0003',
    priority: 4,
    status: 1,
    timeoutSeconds: 15,
  })
  check('update provider', rotated.status === 200 && rotated.body.data.priority === 4, rotated.body)
  check('rotated key not echoed', !JSON.stringify(rotated.body).includes('sk-mock-provider-a-rotated-0003'), rotated.body)

  // 7. 触发扫描：最前面的平台不可达，应自动切到下一个可用平台
  const folders = await call('GET', '/agent/folders', demoToken)
  const tidy = folders.body.data.find((f) => f.name === '个人整理')?.id
  const bounties = await call('GET', '/bounty', adminToken)
  const cooking = bounties.body.data.find((b) => b.title.includes('R-01')) || bounties.body.data[0]
  const before = (await call('GET', '/admin/ai-providers/status', adminToken)).body.data.failoverCount
  // 必须显式开启大模型判定，否则会按用户开关降级为本地规则（这是产品行为）
  const started = await call('POST', '/agent/scan', demoToken, { bountyId: cooking.id, scopeFileIds: [tidy], aiEnabled: true })
  const scanTaskId = started.body?.data?.taskId
  let scanDetail = null
  const scanDeadline = Date.now() + 120000
  while (Date.now() < scanDeadline) {
    const p = await call('GET', '/agent/tasks/' + scanTaskId + '/progress', demoToken)
    if (p.body?.data?.done) {
      scanDetail = (await call('GET', '/agent/tasks/' + scanTaskId, demoToken)).body.data
      break
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  check('scan survives primary failure', !!scanDetail && scanDetail.taskStatus === 2, started.body?.msg)
  check('scan used llm after failover', scanDetail?.llmUsed === true, {
    llmUsed: scanDetail?.llmUsed,
    judged: scanDetail?.llmJudgedCount,
  })
  const afterStatus = (await call('GET', '/admin/ai-providers/status', adminToken)).body.data
  check('failover counted', afterStatus.failoverCount > before, { before, after: afterStatus.failoverCount })
  const brokenInChain = afterStatus.chain.find((c) => c.id === brokenId)
  check('failed provider marked unhealthy', !!brokenInChain && brokenInChain.healthy === false, brokenInChain)
  check('cooldown applied', (brokenInChain?.cooldownSeconds || 0) > 0, brokenInChain)
  const brokenRow = (await call('GET', '/admin/ai-providers', adminToken)).body.data.find((p) => p.id === brokenId)
  check('failure recorded with error', brokenRow?.lastStatus === 'failed' && (brokenRow?.failureCount || 0) >= 1 && !!brokenRow?.lastError, brokenRow)

  // 8. 停用 / 启用 / 排序 / 删除
  const disabled = await call('PUT', '/admin/ai-providers/' + brokenId + '/status?status=0', adminToken)
  check('disable provider', disabled.body.data.status === 0, disabled.body)
  const chainAfterDisable = (await call('GET', '/admin/ai-providers/status', adminToken)).body.data.chain
  check('disabled provider leaves chain', !chainAfterDisable.some((c) => c.id === brokenId), chainAfterDisable)
  const enabled = await call('PUT', '/admin/ai-providers/' + brokenId + '/status?status=1', adminToken)
  check('enable provider', enabled.body.data.status === 1, enabled.body)
  const priority = await call('PUT', '/admin/ai-providers/' + brokenId + '/priority?priority=50', adminToken)
  check('set priority', priority.body.data.priority === 50, priority.body)

  // 9. 权限与校验
  const forbidden = await call('GET', '/admin/ai-providers', demoToken)
  check('non-admin blocked', forbidden.status === 403, forbidden.status)
  const noKey = await call('POST', '/admin/ai-providers', adminToken, { provider: 'x', name: 'x', baseUrl: 'https://x.com', model: 'm', apiKey: '', priority: 5, status: 1, timeoutSeconds: 30 })
  check('missing key rejected', noKey.status === 400, noKey.body)
  const badUrl = await call('POST', '/admin/ai-providers', adminToken, { provider: 'x', name: 'x', baseUrl: 'ftp://x', model: 'm', apiKey: 'k', priority: 5, status: 1, timeoutSeconds: 30 })
  check('invalid base url rejected', badUrl.status === 400, badUrl.body)

  // 10. 环境里 seed 的 DeepSeek 官方平台（临时 Key 可能已失效，仅报告不判定失败）
  const seeded = list.body.data.find((p) => p.provider === 'deepseek')
  if (seeded) {
    const seededTest = await call('POST', '/admin/ai-providers/' + seeded.id + '/test', adminToken)
    console.log('NOTE  DeepSeek 官方平台连通性：' + (seededTest.body.data.ok ? '正常' : seededTest.body.data.message))
  }
} finally {
  for (const id of cleanupIds) {
    await call('DELETE', '/admin/ai-providers/' + id, (await call('POST', '/auth/login', null, { username: 'admin', password: 'admin123' })).body?.data?.token)
  }
  mock.kill()
}

console.log('')
console.log('FAILURES: ' + failed)
process.exit(failed === 0 ? 0 : 1)
