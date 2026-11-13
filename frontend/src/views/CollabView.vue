<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

const sessions = ref([])
const templates = ref([])
const folders = ref([])
const loading = ref(false)
const inviteVisible = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const treeRef = ref(null)

const inviteForm = reactive({ partnerId: 0, templateKey: '', title: '', message: '', kThreshold: 3 })
const partners = ref([])

const STATE = {
  0: { text: '待对方接受', type: 'warning' },
  1: { text: '已接受', type: 'primary' },
  2: { text: '双方贡献中', type: 'primary' },
  3: { text: '已合并', type: 'success' },
  4: { text: '已取消', type: 'info' },
}

const stepIndex = computed(() => {
  const s = detail.value?.session
  if (!s) return 0
  return { 0: 1, 1: 2, 2: 3, 3: 4, 4: 0 }[s.state] || 1
})

function parseJson(text, fallback) {
  try { return JSON.parse(text) } catch { return fallback }
}

function stateOf(session) {
  return STATE[session.state] || STATE[0]
}

const treeData = computed(() => {
  const nodes = new Map()
  folders.value.forEach((row) => nodes.set(row.id, { id: row.id, label: row.name, childCount: row.childCount, children: [] }))
  const roots = []
  folders.value.forEach((row) => {
    const node = nodes.get(row.id)
    if (row.parentId && nodes.has(row.parentId)) nodes.get(row.parentId).children.push(node)
    else roots.push(node)
  })
  return roots
})

async function load() {
  loading.value = true
  try {
    const [list, tpl, folderList] = await Promise.all([
      api.collabList(), api.sellerTemplates(), api.sellerFolders(),
    ])
    sessions.value = list
    templates.value = tpl
    folders.value = folderList
    if (!inviteForm.templateKey && tpl.length) inviteForm.templateKey = tpl[0].key
  } finally {
    loading.value = false
  }
}

async function openInvite() {
  inviteForm.partnerId = 0
  inviteForm.title = ''
  inviteForm.message = ''
  partners.value = []
  inviteVisible.value = true
}

async function submitInvite() {
  if (!inviteForm.partnerId) {
    ElMessage.warning('请填写协同伙伴的用户 ID')
    return
  }
  await api.collabInvite({ ...inviteForm })
  ElMessage.success('协同邀请已发出')
  inviteVisible.value = false
  await load()
}

async function openDetail(session) {
  detail.value = await api.collabDetail(session.id)
  detailVisible.value = true
}

async function accept(session) {
  await api.collabAccept(session.id)
  ElMessage.success('已接受邀请')
  await load()
}

async function reject(session) {
  try {
    await ElMessageBox.confirm('取消后对方收到通知，联合产品不会生成。继续？', '取消协同', { type: 'warning' })
    await api.collabReject(session.id)
    await load()
  } catch (e) { /* 取消 */ }
}

async function contribute() {
  const checked = (treeRef.value && treeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选要参与联合产品的文件夹')
    return
  }
  await api.collabContribute(detail.value.session.id, { scopeFileIds: checked, aiEnabled: true, approve: true })
  ElMessage.success('已提交本地聚合结果（不含原始行）')
  detail.value = await api.collabDetail(detail.value.session.id)
  await load()
}

async function merge() {
  try {
    await ElMessageBox.confirm('合并后生成联合数据产品，只保留双方都覆盖到的分组。继续？', '合并联合产品', { type: 'warning' })
    await api.collabMerge(detail.value.session.id)
    ElMessage.success('联合产品已生成，可到卖方工作台上架')
    detailVisible.value = false
    await load()
  } catch (e) { /* 取消 */ }
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>Agent 协同</h2>
        <p class="page-sub">
          两个卖方 Agent 各自在本地聚合，只交换<strong>分组统计</strong>，合并出联合数据产品。原始行永远不出各自网盘。
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openInvite">发起协同</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="sessions" class="file-table">
      <el-table-column prop="id" label="会话" width="70" />
      <el-table-column prop="title" label="联合产品" min-width="200" />
      <el-table-column prop="templateLabel" label="模板" width="140" />
      <el-table-column label="双方" min-width="160">
        <template #default="{ row }">{{ row.initiatorName }} ↔ {{ row.partnerName }}</template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }"><el-tag size="small" :type="stateOf(row).type" effect="plain">{{ stateOf(row).text }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="createdAt" label="发起时间" width="170" />
      <el-table-column label="操作" width="180" align="right">
        <template #default="{ row }">
          <el-button v-if="row.state === 0" text size="small" type="primary" @click="accept(row)">接受</el-button>
          <el-button v-if="row.state < 3" text size="small" @click="reject(row)">取消</el-button>
          <el-button text size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !sessions.length" description="还没有协同会话，发起一个试试" />

    <el-dialog v-model="inviteVisible" title="发起 Agent 协同" width="560px">
      <el-form label-width="110px">
        <el-form-item label="伙伴用户 ID">
          <el-input-number v-model="inviteForm.partnerId" :min="1" />
          <div class="form-hint">演示环境：seller_a=3、seller_b=4（可在管理后台查用户 ID）</div>
        </el-form-item>
        <el-form-item label="产品模板">
          <el-select v-model="inviteForm.templateKey" style="width: 100%">
            <el-option v-for="tpl in templates" :key="tpl.key" :label="tpl.label" :value="tpl.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="inviteForm.title" placeholder="留空自动命名" /></el-form-item>
        <el-form-item label="k 阈值"><el-input-number v-model="inviteForm.kThreshold" :min="2" :max="50" /></el-form-item>
        <el-form-item label="留言"><el-input v-model="inviteForm.message" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInvite">发出邀请</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="detail?.session?.title || '协同详情'" size="56%">
      <div v-if="detail">
        <el-steps :active="stepIndex" align-center finish-status="success" class="collab-steps">
          <el-step title="邀请" />
          <el-step title="接受" />
          <el-step title="双方贡献" />
          <el-step title="合并" />
        </el-steps>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="模板">{{ detail.session.templateLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ stateOf(detail.session).text }}</el-descriptions-item>
          <el-descriptions-item label="发起方">{{ detail.session.initiatorName }}</el-descriptions-item>
          <el-descriptions-item label="伙伴">{{ detail.session.partnerName }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">贡献情况（只含分组统计）</h4>
        <el-table :data="detail.contributions" size="small">
          <el-table-column prop="userName" label="参与方" width="120" />
          <el-table-column prop="groupCount" label="贡献行数" width="110" />
          <el-table-column prop="suppressedGroups" label="丢弃少样本组" width="120" />
          <el-table-column label="确认" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.approved === 1 ? 'success' : 'info'" effect="plain">{{ row.approved === 1 ? '已确认' : '未确认' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <h4 class="section-title">我会贡献的目录</h4>
        <el-tree
          ref="treeRef"
          :data="treeData"
          show-checkbox
          node-key="id"
          default-expand-all
          :props="{ label: 'label', children: 'children' }"
          class="scope-tree"
        />

        <h4 class="section-title">合并预览（双方共同分组）</h4>
        <div v-if="detail.mergedPreview.length" class="preview-text">
          <el-table :data="detail.mergedPreview" size="small">
            <el-table-column
              v-for="(key, idx) in Object.keys(detail.mergedPreview[0])"
              :key="idx"
              :label="key"
              min-width="110"
            >
              <template #default="{ row }">{{ row[key] }}</template>
            </el-table-column>
          </el-table>
        </div>
        <p v-else class="form-hint">双方都提交贡献后，这里会显示交集分组。</p>

        <div class="collab-actions">
          <el-button type="primary" plain @click="contribute">提交我的聚合结果</el-button>
          <el-button type="success" :disabled="detail.contributions.length < 2" @click="merge">合并为联合产品</el-button>
        </div>
      </div>
    </el-drawer>
  </main>
</template>
