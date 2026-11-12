// IntelliJ IDEA MCP 客户端：streamable-http，带 session 复用
const URL_ = 'http://127.0.0.1:64342/stream'
const HDR = { 'IJ_MCP_SERVER_PROJECT_PATH': 'E:/Code/AgentCloudDisk/backend' }
let sessionId = null
let seq = 1000

export async function rpc(method, params) {
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
    ...HDR,
  }
  if (sessionId) headers['mcp-session-id'] = sessionId
  const res = await fetch(URL_, {
    method: 'POST',
    headers,
    body: JSON.stringify({ jsonrpc: '2.0', id: ++seq, method, params }),
  })
  const sid = res.headers.get('mcp-session-id')
  if (sid) sessionId = sid
  const text = await res.text()
  const trimmed = text.trim()
  if (trimmed.startsWith('{')) {
    const json = JSON.parse(trimmed)
    if (json.error) throw new Error(JSON.stringify(json.error))
    return json.result
  }
  // SSE 帧
  const lines = trimmed.split('\n').filter((l) => l.startsWith('data:'))
  for (const line of lines) {
    try {
      const json = JSON.parse(line.slice(5).trim())
      if (json.result) return json.result
      if (json.error) throw new Error(JSON.stringify(json.error))
    } catch (e) { /* 继续 */ }
  }
  return { raw: text.slice(0, 500) }
}

export async function init() {
  return rpc('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'dsh', version: '1.0.0' },
  })
}

export async function callTool(name, args) {
  return rpc('tools/call', { name, arguments: args })
}
