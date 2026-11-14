import http from './http'

export const api = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),

  list: (parentId, keyword = '') => http.get('/files', { params: { parentId, keyword } }),
  quota: () => http.get('/files/quota'),
  createFolder: (data) => http.post('/files/folder', data),
  rename: (id, name) => http.put(`/files/${id}/rename`, { name }),
  remove: (id) => http.delete(`/files/${id}`),

  recycle: () => http.get('/files/recycle'),
  restore: (id) => http.post(`/files/recycle/${id}/restore`),
  purge: (id) => http.delete(`/files/recycle/${id}`),
  emptyRecycle: () => http.delete('/files/recycle'),

  content: (id, download = false) =>
    http.get(`/files/${id}/content`, { params: { download }, responseType: 'blob' }),

  uploadSmall: (file, parentId) => {
    const form = new FormData()
    form.append('file', file)
    if (parentId != null) form.append('parentId', String(parentId))
    return http.post('/upload/small', form)
  },
  initChunk: (data) => http.post('/upload/chunk/init', data),
  uploadChunk: (taskId, index, blob) => {
    const form = new FormData()
    form.append('chunk', blob)
    return http.post(`/upload/chunk?taskId=${taskId}&index=${index}`, form)
  },
  mergeChunk: (data) => http.post('/upload/chunk/merge', data),

  bountyList: (status) => http.get('/bounty', { params: { status } }),
  bountyCreate: (data) => http.post('/bounty', data),
  bountyDetail: (id) => http.get('/bounty/' + id),
  bountyStatus: (id, status) => http.put('/bounty/' + id + '/status?status=' + status),

  agentFolders: () => http.get('/agent/folders'),
  agentLlmStatus: () => http.get('/agent/llm-status'),
  agentScan: (data) => http.post('/agent/scan', data),
  agentTasks: () => http.get('/agent/tasks'),
  agentTask: (id) => http.get('/agent/tasks/' + id),
  agentProgress: (id) => http.get('/agent/tasks/' + id + '/progress'),
  agentPreview: (resultId) => http.get('/agent/results/' + resultId + '/preview'),
  agentReview: (id, data) => http.put('/agent/results/' + id + '/review', data),

  adminBounties: (status) => http.get('/admin/bounties', { params: { status } }),
  adminBountyStatus: (id, status) => http.put('/admin/bounties/' + id + '/status?status=' + status),
  adminScanTasks: (bountyId) => http.get('/admin/scan-tasks', { params: { bountyId } }),

  // ---------- 卖方 Agent ----------
  sellerTemplates: () => http.get('/seller/templates'),
  sellerFolders: () => http.get('/seller/folders'),
  sellerProducts: () => http.get('/seller/products'),
  sellerProduct: (id) => http.get('/seller/products/' + id),
  sellerIntent: (data) => http.post('/seller/intent', data),
  sellerCreate: (data) => http.post('/seller/products', data),
  sellerPublish: (id, data) => http.post('/seller/products/' + id + '/publish', data),
  sellerDelist: (id) => http.post('/seller/products/' + id + '/delist'),
  sellerDelete: (id) => http.delete('/seller/products/' + id),
  sellerBuildProgress: (taskId) => http.get('/seller/build/' + taskId + '/progress'),
  sellerDeliveries: () => http.get('/seller/deliveries'),
  sellerAcceptDelivery: (id) => http.post('/seller/deliveries/' + id + '/accept'),
  sellerCompleteDelivery: (id) => http.post('/seller/deliveries/' + id + '/complete'),

  // ---------- 数据市场（买方） ----------
  marketListings: (params) => http.get('/market/listings', { params }),
  marketListing: (id) => http.get('/market/listings/' + id),
  marketDemands: () => http.get('/market/demands'),
  marketCreateDemand: (data) => http.post('/market/demands', data),
  marketMatch: (id) => http.post('/market/demands/' + id + '/match'),
  marketPushes: () => http.get('/market/pushes'),
  marketReadPush: (id) => http.post('/market/pushes/' + id + '/read'),
  marketRequestDelivery: (id, note) => http.post('/market/pushes/' + id + '/delivery', null, { params: { note } }),
  marketDeliveries: () => http.get('/market/deliveries'),
  marketStats: () => http.get('/market/stats'),

  // ---------- Agent 协同 ----------
  collabList: () => http.get('/market/collab'),
  collabDetail: (id) => http.get('/market/collab/' + id),
  collabInvite: (data) => http.post('/market/collab/invitations', data),
  collabAccept: (id) => http.post('/market/collab/' + id + '/accept'),
  collabReject: (id) => http.post('/market/collab/' + id + '/reject'),
  collabContribute: (id, data) => http.post('/market/collab/' + id + '/contribute', data),
  collabMerge: (id) => http.post('/market/collab/' + id + '/merge'),

  aiProviders: () => http.get('/admin/ai-providers'),
  aiProviderStatus: () => http.get('/admin/ai-providers/status'),
  aiProviderCreate: (data) => http.post('/admin/ai-providers', data),
  aiProviderUpdate: (id, data) => http.put('/admin/ai-providers/' + id, data),
  aiProviderDelete: (id) => http.delete('/admin/ai-providers/' + id),
  aiProviderTest: (id) => http.post('/admin/ai-providers/' + id + '/test'),
  aiProviderStatusToggle: (id, status) => http.put('/admin/ai-providers/' + id + '/status?status=' + status),
  aiProviderPriority: (id, priority) => http.put('/admin/ai-providers/' + id + '/priority?priority=' + priority),

  adminStats: () => http.get('/admin/stats'),
  adminUsers: (keyword = '') => http.get('/admin/users', { params: { keyword } }),
  adminStatus: (id, status) => http.put(`/admin/users/${id}/status?status=${status}`),
  adminQuota: (id, totalQuota) => http.put(`/admin/users/${id}/quota?totalQuota=${totalQuota}`),
}
