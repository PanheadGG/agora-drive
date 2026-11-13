<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'
import { formatBytes } from '../utils/format'

const templates = ref([])
const folders = ref([])
const products = ref([])
const loading = ref(false)
const creating = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const treeRef = ref(null)

const progressVisible = ref(false)
const progress = ref(null)
const progressEvents = ref([])
let pollTimer = null

const form = reactive({
  templateKey: 'commute_stats',
  title: '',
  aiEnabled: false,
  scopeFileIds: [],
})

const publishVisible = ref(false)
const publishForm = reactive({ title: '', summary: '', priceCents: 0, licenseType: 'internal' })
const publishingId = ref(0)

const currentTemplate = computed(() => templates.value.find((t) => t.key === form.templateKey))

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

const STATUS = { 0: { text: '草稿', type: 'info' }, 1: { text: '已上架', type: 'success' }, 2: { text: '已下架', type: 'info' } }

function statusOf(product) {
  return STATUS[product.status] || STATUS[0]
}

function parseJson(text, fallback) {
  try { return JSON.parse(text) } catch { return fallback }
}

function qualityOf(product) {
  return parseJson(product.qualityJson, {})
}

function tagsOf(product) {
  return parseJson(product.tagsJson, [])
}

async function load() {
  loading.value = true
  try {
    const [tpl, folderList, productList] = await Promise.all([
      api.sellerTemplates(), api.sellerFolders(), api.sellerProducts(),
    ])
    templates.value = tpl
    folders.value = folderList
    products.value = productList
    if (!tpl.some((t) => t.key === form.templateKey) && tpl.length) form.templateKey = tpl[0].key
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.title = ''
  form.aiEnabled = false
  form.scopeFileIds = []
  dialogVisible.value = true
}

function stopPolling() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
}

async function pollBuild(taskId) {
  try {
    const data = await api.sellerBuildProgress(taskId)
    progress.value = data
    if (data.events && data.events.length) progressEvents.value = data.events
    if (data.done) {
      stopPolling()
      creating.value = false
      progressVisible.value = false
      await load()
      ElMessage.success('数据产品已生成')
      return
    }
    pollTimer = setTimeout(() => pollBuild(taskId), 400)
  } catch (e) {
    stopPolling()
    creating.value = false
  }
}

async function submitCreate() {
  const checked = (treeRef.value && treeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选要加工的文件夹（Agent 只会读这个范围）')
    return
  }
  creating.value = true
  progressEvents.value = []
  progress.value = { stage: '排队中', processed: 0, total: 0, percent: 0 }
  progressVisible.value = true
  try {
    const res = await api.sellerCreate({
      templateKey: form.templateKey,
      title: form.title.trim(),
      scopeFileIds: checked,
      aiEnabled: form.aiEnabled,
    })
    dialogVisible.value = false
    pollBuild(res.taskId)
  } catch (e) {
    creating.value = false
    progressVisible.value = false
  }
}

async function openDetail(product) {
  detail.value = await api.sellerProduct(product.id)
  detailVisible.value = true
}

function openPublish(product) {
  publishingId.value = product.id
  publishForm.title = product.title
  publishForm.summary = ''
  publishForm.priceCents = 5000
  publishForm.licenseType = 'internal'
  publishVisible.value = true
}

async function submitPublish() {
  await api.sellerPublish(publishingId.value, { ...publishForm })
  ElMessage.success('已上架到数据市场')
  publishVisible.value = false
  await load()
}

async function delist(product) {
  try {
    await ElMessageBox.confirm('下架后买方将搜不到该货源，已推送的匹配会失效。继续？', '下架', { type: 'warning' })
    await api.sellerDelist(product.id)
    ElMessage.success('已下架')
    await load()
  } catch (e) { /* 取消 */ }
}

async function remove(product) {
  try {
    await ElMessageBox.confirm('删除产品不可恢复（原始文件不受影响）。继续？', '删除产品', { type: 'warning' })
    await api.sellerDelete(product.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 取消 */ }
}

onMounted(load)
onBeforeUnmount(stopPolling)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>卖方工作台</h2>
        <p class="page-sub">
          你的 Agent 会在你勾选的范围内读取原始文件，按模板加工成<strong>数据产品</strong>（只含聚合结果，不含原始行），再上架到数据市场。
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建数据产品</el-button>
      </div>
    </div>

    <div v-loading="loading" class="product-grid">
      <el-card v-for="product in products" :key="product.id" shadow="never" class="product-card">
        <div class="product-head">
          <span class="product-title">{{ product.title }}</span>
          <el-tag size="small" :type="statusOf(product).type" effect="plain">{{ statusOf(product).text }}</el-tag>
        </div>
        <div class="product-meta">
          <el-tag size="small" effect="dark" type="primary">{{ product.templateLabel }}</el-tag>
          <el-tag v-if="product.sourceKind === 'joint'" size="small" type="warning" effect="plain">联合产品</el-tag>
          <el-tag v-for="tag in tagsOf(product).slice(0, 3)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>

        <div class="product-stats">
          <div><span class="num">{{ qualityOf(product).groups || 0 }}</span><span>分组</span></div>
          <div><span class="num">{{ product.rowCount }}</span><span>原始行</span></div>
          <div><span class="num">{{ product.contributingFiles }}</span><span>来源文件</span></div>
          <div><span class="num">{{ qualityOf(product).score || 0 }}</span><span>质量分</span></div>
        </div>

        <p class="product-note">
          覆盖：{{ product.coverageStart || '—' }} ~ {{ product.coverageEnd || '—' }} ·
          k-匿名 k={{ qualityOf(product).kAnonymity || 3 }} ·
          丢弃过少分组 {{ qualityOf(product).suppressedGroups || 0 }} 个
        </p>

        <div class="product-foot">
          <el-button text size="small" @click="openDetail(product)">构建凭证</el-button>
          <div>
            <el-button v-if="product.status !== 1" size="small" type="primary" plain :disabled="!product.rowCount" @click="openPublish(product)">上架</el-button>
            <el-button v-else size="small" text @click="delist(product)">下架</el-button>
            <el-button size="small" text type="danger" @click="remove(product)">删除</el-button>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && !products.length" description="还没有数据产品，点右上角新建一个" />
    </div>

    <!-- 新建产品 -->
    <el-dialog v-model="dialogVisible" title="新建数据产品" width="680px">
      <el-form label-width="110px">
        <el-form-item label="产品模板">
          <el-select v-model="form.templateKey" style="width: 100%">
            <el-option v-for="tpl in templates" :key="tpl.key" :label="tpl.label" :value="tpl.key" />
          </el-select>
          <div class="form-hint" v-if="currentTemplate">
            {{ currentTemplate.description }}；输出字段：{{ currentTemplate.fields.map((f) => f.label).join('、') }}
          </div>
        </el-form-item>
        <el-form-item label="产品名称">
          <el-input v-model="form.title" :placeholder="currentTemplate ? currentTemplate.label : '留空自动命名'" />
        </el-form-item>
        <el-form-item label="授权范围">
          <div class="scope-box">
            <el-tree
              ref="treeRef"
              :data="treeData"
              show-checkbox
              node-key="id"
              default-expand-all
              :props="{ label: 'label', children: 'children' }"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span>{{ data.label }}</span>
                  <span class="tree-count">{{ data.childCount }} 项</span>
                </span>
              </template>
            </el-tree>
            <p class="form-hint">未勾选的文件夹不会被读取；含身份证/手机号的文件在出厂前会被自动排除。</p>
          </div>
        </el-form-item>
        <el-form-item label="大模型参与">
          <el-switch v-model="form.aiEnabled" />
          <span class="form-hint inline">{{ form.aiEnabled ? '会用模型生成产品标签' : '只用本地规则加工' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">开始加工</el-button>
      </template>
    </el-dialog>

    <!-- 加工进度 -->
    <el-dialog v-model="progressVisible" title="Agent 正在加工" width="560px" :close-on-click-modal="false">
      <div class="scan-stage">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ progress?.stage || '准备中…' }}</span>
        <span v-if="progress?.currentFile" class="scan-current">{{ progress.currentFile }}</span>
      </div>
      <el-progress :percentage="progress?.percent || 0" :stroke-width="10" />
      <div class="scan-stats">
        <span>已处理 {{ progress?.processed || 0 }} / {{ progress?.total || 0 }}</span>
      </div>
      <div class="scan-events">
        <div v-for="event in progressEvents.slice().reverse()" :key="event.seq" class="scan-event">
          <el-tag size="small" :type="event.riskTag ? 'danger' : event.ok ? 'success' : 'info'" effect="plain">{{ event.stage }}</el-tag>
          <span class="scan-event-name">{{ event.fileName }}</span>
          <span class="scan-event-method">{{ event.parseMethod }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 上架 -->
    <el-dialog v-model="publishVisible" title="上架到数据市场" width="560px">
      <el-form label-width="100px">
        <el-form-item label="标题"><el-input v-model="publishForm.title" /></el-form-item>
        <el-form-item label="摘要"><el-input v-model="publishForm.summary" type="textarea" :rows="3" placeholder="买方看到的描述，不要写原始文件名" /></el-form-item>
        <el-form-item label="价格(分)"><el-input-number v-model="publishForm.priceCents" :min="0" :step="1000" /></el-form-item>
        <el-form-item label="许可">
          <el-select v-model="publishForm.licenseType">
            <el-option label="仅内部使用" value="internal" />
            <el-option label="可再分发" value="open" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPublish">确认上架</el-button>
      </template>
    </el-dialog>

    <!-- 构建凭证 -->
    <el-drawer v-model="detailVisible" title="构建凭证（可复现）" size="56%">
      <div v-if="detail" class="credential">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="产品">{{ detail.product.title }}</el-descriptions-item>
          <el-descriptions-item label="模板">{{ detail.product.templateLabel }}</el-descriptions-item>
          <el-descriptions-item label="分组数">{{ parseJson(detail.product.qualityJson, {}).groups }}</el-descriptions-item>
          <el-descriptions-item label="原始行">{{ detail.product.rowCount }}</el-descriptions-item>
          <el-descriptions-item label="覆盖区间">{{ detail.product.coverageStart || '—' }} ~ {{ detail.product.coverageEnd || '—' }}</el-descriptions-item>
          <el-descriptions-item label="k-匿名">k={{ parseJson(detail.product.qualityJson, {}).kAnonymity }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">原料溯源</h4>
        <el-table :data="detail.items" size="small">
          <el-table-column prop="fileName" label="文件" min-width="160" />
          <el-table-column prop="parseMethod" label="解析方式" width="140" />
          <el-table-column prop="rowsContributed" label="贡献行" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                {{ row.status === 1 ? '已采用' : '已排除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
        </el-table>

        <h4 class="section-title">聚合结果（市场可见的样例）</h4>
        <pre class="preview-text">{{ detail.product.sampleJson }}</pre>
      </div>
    </el-drawer>
  </main>
</template>
