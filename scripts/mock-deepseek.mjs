// 本地 DeepSeek 兼容 Mock 服务：用于在没有真实 API Key 时验证「请求 → 解析 → 证据落地」全链路
// 用法：node scripts/mock-deepseek.mjs [port]
import { createServer } from 'node:http'

const PORT = Number(process.argv[2] || 9099)
let callCount = 0

const respond = (res, status, obj) => {
  const body = JSON.stringify(obj)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) })
  res.end(body)
}

const completion = (content) => ({
  id: 'mock-' + (++callCount),
  object: 'chat.completion',
  created: Math.floor(Date.now() / 1000),
  model: 'deepseek-mock',
  choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
  usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 },
})

function structureBounty(userPrompt) {
  const text = userPrompt.toLowerCase()
  const domain = text.includes('菜谱') || text.includes('做法') || text.includes('做饭')
    ? { domain: 'cooking', label: '家常做法/菜谱', keywords: ['菜谱', '做法', '食材', '步骤', '家常', '红烧', '翻炒', '出锅'] }
    : text.includes('徒步') || text.includes('爬山')
      ? { domain: 'hiking', label: '徒步/爬山路线', keywords: ['徒步', '爬山', '路线', '公里', '岔路', '补给', '海拔'] }
      : text.includes('猫') || text.includes('宠物')
        ? { domain: 'pet', label: '家养宠物日常记录', keywords: ['猫', '喂养', '体重', '驱虫', '换粮'] }
        : text.includes('读后') || text.includes('读书')
          ? { domain: 'reading', label: '读书笔记/读后感', keywords: ['读后感', '读书', '摘抄', '笔记', '书中', '作者'] }
          : text.includes('通勤')
            ? { domain: 'commute', label: '日常通勤记录', keywords: ['通勤', '地铁', '公交', '出发', '到达', '换乘'] }
            : { domain: 'generic', label: '通用资料', keywords: ['文档', '表格', '记录'] }
  return {
    domain: domain.domain,
    domainLabel: domain.label,
    wants: ['用户自己整理的相关记录', '用户自己拍的照片', '用户自己整理的表格'],
    formats: ['image', 'docx', 'xlsx', 'pdf'],
    matchKeywords: domain.keywords,
    excludeKeywords: ['小红书', '公众号', '转载', '网红', '宣传'],
    yearLimit: 2,
    understanding: '这条悬赏要的是用户自己整理的「' + domain.label + '」相关原创内容，不要网络搬运与含敏感信息的文件',
  }
}

function judgeFiles(userPrompt) {
  const keywordLine = (userPrompt.match(/匹配关键词：(.+)/) || [])[1] || ''
  const keywords = keywordLine.split('、').map((item) => item.trim()).filter(Boolean)
  const blocks = userPrompt.split('---- ').slice(1)
  const results = []
  for (const block of blocks) {
    const idMatch = block.match(/文件 id=(\d+)/)
    if (!idMatch) continue
    const fileId = Number(idMatch[1])
    const body = block.split('\n').slice(1).join('\n')
    const hits = keywords.filter((keyword) => body.includes(keyword))
    let matchLevel = hits.length >= 3 ? 3 : hits.length === 2 ? 2 : hits.length === 1 ? 1 : 0
    const index = hits.length ? body.indexOf(hits[0]) : -1
    const evidence = index >= 0 ? body.slice(index, index + 60).replace(/\s+/g, ' ').trim() : ''
    results.push({
      fileId,
      matchLevel,
      evidence,
      riskTag: null,
      riskDesc: null,
      reason: hits.length ? '命中关键词：' + hits.join('、') : '正文与需求无关',
    })
  }
  return { results }
}

createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.startsWith('/chat/completions')) {
    respond(res, 404, { error: { message: 'not found' } })
    return
  }
  let raw = ''
  req.on('data', (chunk) => { raw += chunk })
  req.on('end', () => {
    try {
      const body = JSON.parse(raw)
      if (body.response_format && body.response_format.type !== 'json_object') {
        respond(res, 400, { error: { message: 'response_format must be json_object' } })
        return
      }
      const system = (body.messages || []).find((msg) => msg.role === 'system')?.content || ''
      const user = (body.messages || []).find((msg) => msg.role === 'user')?.content || ''
      const payload = system.includes('悬赏解析器') ? structureBounty(user) : judgeFiles(user)
      respond(res, 200, completion(JSON.stringify(payload)))
    } catch (error) {
      respond(res, 500, { error: { message: String(error) } })
    }
  })
}).listen(PORT, '127.0.0.1', () => console.log('mock deepseek listening on ' + PORT))
