<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

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

// 普通用户视角：只说想做什么，不暴露模板/字段/开关
const form = reactive({
  description: '',
  title: '',
})
const intent = ref(null)
const intentLoading = ref(false)
let intentTimer = null
// 大模型默认参与，不再给用户一个难懂的选择项
const AI_ENABLED = true

const EXAMPLES = [
  '把我记录的通勤时间整理成按线路的平均耗时',
  '把我的家常菜谱文档整理成可查的菜谱库',
  '把猫咪的喂养表格整理成体重和驱虫记录',
  '把读后感整理成按书目的笔记统计',
]

const publishVisible = ref(false)
const publishForm = reactive({ title: '', summary: '', priceCents: 0, licenseType: 'internal' })
const publishingId = ref(0)

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

const STATUS = { 0: { text: '待上架', type: 'warning' }, 1: { text: '已上架', type: 'success' }, 2: { text: '已下架', type: 'info' } }

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

/** 把内部字段名翻译成人话，普通用户不需要看到 avg_duration */
const MEASURE_LABELS = {
  avg_duration: '平均耗时',
  p90_duration: '90分位耗时',
  sample_count: '样本数',
  ingredient_count: '食材数',
  step_count: '步骤数',
  avg_weight: '平均体重',
  record_count: '记录数',
  note_count: '笔记数',
  topic_variety: '主题多样性',
  sources: '来源数',
}

/** 产品为空时告诉用户具体怎么办，而不是让 ta 猜 */
function emptyHint(product) {
  const excluded = parseJson(product.buildReceipt, {}).excluded || []
  if (excluded.length) {
    return '你的 ' + excluded.length + ' 个文件都没能整理出相关数据。可以换个文件夹，或者换个说法重新描述一次。'
  }
  return '试试换个说法重新描述，或者勾选包含这类数据的文件夹。'
}

/** 永远返回 {keys, rows} 结构，模板里可以放心取 .rows.length */
function previewRows(product) {
  const rows = parseJson(product.sampleJson, [])
  if (!Array.isArray(rows) || !rows.length) return { keys: [], rows: [] }
  const keys = Object.keys(rows[0])
  return {
    keys: keys.map((k) => ({ key: k, label: MEASURE_LABELS[k] || k })),
    rows: rows.slice(0, 4),
  }
}

async function load() {
  loading.value = true
  try {
    const [folderList, productList] = await Promise.all([api.sellerFolders(), api.sellerProducts()])
    folders.value = folderList
    products.value = productList
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.description = ''
  form.title = ''
  intent.value = null
  dialogVisible.value = true
}

function onDescriptionInput() {
  if (intentTimer) clearTimeout(intentTimer)
  const text = form.description.trim()
  if (text.length < 4) {
    intent.value = null
    return
  }
  intentLoading.value = true
  intentTimer = setTimeout(async () => {
    try {
      intent.value = await api.sellerIntent({ description: text, aiEnabled: AI_ENABLED })
    } finally {
      intentLoading.value = false
    }
  }, 500)
}

function useExample(text) {
  form.description = text
  onDescriptionInput()
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
      ElMessage.success('数据产品已生成，可以上架了')
      return
    }
    pollTimer = setTimeout(() => pollBuild(taskId), 400)
  } catch (e) {
    stopPolling()
    creating.value = false
  }
}

async function submitCreate() {
  if (form.description.trim().length < 4) {
    ElMessage.warning('用一句话说说你想整理出什么，例如「把我记的通勤时间整理成按线路的平均耗时」')
    return
  }
  const checked = (treeRef.value && treeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选要整理的文件或文件夹')
    return
  }
  creating.value = true
  progressEvents.value = []
  progress.value = { stage: '排队中', processed: 0, total: 0, percent: 0 }
  progressVisible.value = true
  try {
    const res = await api.sellerCreate({
      description: form.description.trim(),
      title: form.title.trim(),
      scopeFileIds: checked,
      aiEnabled: AI_ENABLED,
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
    await ElMessageBox.confirm('下架后买方将搜不到这个产品，已推送的匹配会失效。继续？', '下架', { type: 'warning' })
    await api.sellerDelist(product.id)
    ElMessage.success('已下架')
    await load()
  } catch (e) { /* 取消 */ }
}

async function remove(product) {
  try {
    await ElMessageBox.confirm('删除后不可恢复（你的原始文件不受影响）。继续？', '删除产品', { type: 'warning' })
    await api.sellerDelete(product.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 取消 */ }
}

onMounted(load)
onBeforeUnmount(() => { stopPolling(); if (intentTimer) clearTimeout(intentTimer) })
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>我的数据产品</h2>
        <p class="page-sub">
          用一句话告诉 Agent 你想整理出什么，它会读你选中的文件，整理成一个可以上架的数据产品。
          <strong>上架前不会有人看到你的原始文件。</strong>
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
          <el-tag v-for="tag in tagsOf(product).slice(0, 3)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>

        <div class="product-stats">
          <div><span class="num">{{ qualityOf(product).groups || 0 }}</span><span>整理出 {{ qualityOf(product).groups || 0 }} 组</span></div>
          <div><span class="num">{{ product.contributingFiles }}</span><span>用了 {{ product.contributingFiles }} 个文件</span></div>
          <div><span class="num">{{ qualityOf(product).score || 0 }}</span><span>完整度 {{ qualityOf(product).score || 0 }} 分</span></div>
        </div>

        <div v-if="previewRows(product).rows.length" class="product-sample">
          <div class="sample-title">整理结果预览</div>
          <el-table :data="previewRows(product).rows" size="small">
            <el-table-column
              v-for="col in previewRows(product).keys"
              :key="col.key"
              :label="col.label"
              min-width="90"
            >
              <template #default="{ row }">{{ row[col.key] }}</template>
            </el-table-column>
          </el-table>
        </div>

        <el-alert
          v-if="!product.rowCount"
          type="warning"
          :closable="false"
          show-icon
          class="product-empty-alert"
          title="没在你选的文件里找到匹配这个需求的内容"
          :description="emptyHint(product)"
        />

        <p v-else class="product-note">
          为保护隐私：每组至少 {{ qualityOf(product).kAnonymity || 3 }} 条才会展示，
          已自动略过 {{ qualityOf(product).suppressedGroups || 0 }} 组数据太少的。
        </p>

        <div class="product-foot">
          <el-button text size="small" @click="openDetail(product)">查看明细</el-button>
          <div>
            <el-button v-if="product.status !== 1" size="small" type="primary" plain :disabled="!product.rowCount" @click="openPublish(product)">上架</el-button>
            <el-button v-else size="small" text @click="delist(product)">下架</el-button>
            <el-button size="small" text type="danger" @click="remove(product)">删除</el-button>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && !products.length" description="还没有数据产品，点右上角新建一个" />
    </div>

    <!-- 新建：一句话描述 -->
    <el-dialog v-model="dialogVisible" title="新建数据产品" width="680px">
      <el-form label-position="top">
        <el-form-item label="你想整理出什么？">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="用一句话说就行，例如：把我记录的通勤时间整理成按线路的平均耗时"
            @input="onDescriptionInput"
          />
        </el-form-item>

        <div class="example-row">
          <span class="example-label">试试：</span>
          <el-tag
            v-for="example in EXAMPLES"
            :key="example"
            size="small"
            effect="plain"
            class="example-tag"
            @click="useExample(example)"
          >{{ example }}</el-tag>
        </div>

        <div v-if="intentLoading" class="intent-box intent-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>Agent 正在理解你的需求…</span>
        </div>
        <div v-else-if="intent" class="intent-box">
          <div class="intent-head">
            <span class="intent-title">
              Agent 理解你要做：<strong>{{ intent.templateLabel }}</strong>
            </span>
            <el-tag size="small" :type="intent.needsConfirm ? 'warning' : 'success'" effect="plain">
              已理解 {{ intent.confidence }}%
            </el-tag>
          </div>
          <p class="intent-reason">{{ intent.reason }}</p>
          <p class="intent-fields">
            会整理出：{{ intent.fields.join('、') }}
          </p>
        </div>

        <el-form-item label="选择要整理的文件">
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
          </div>
          <div class="form-hint">
            只读你勾选的文件；含身份证、手机号等敏感内容的文件会自动排除，不会进入产品。
          </div>
        </el-form-item>

        <el-form-item label="给它起个名字（可选）">
          <el-input v-model="form.title" :placeholder="intent ? intent.templateLabel : '留空自动命名'" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">开始整理</el-button>
      </template>
    </el-dialog>

    <!-- 整理进度 -->
    <el-dialog v-model="progressVisible" title="Agent 正在整理" width="560px" :close-on-click-modal="false">
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
        <el-form-item label="介绍">
          <el-input v-model="publishForm.summary" type="textarea" :rows="3" placeholder="买家会看到这段介绍，不要写原始文件名" />
        </el-form-item>
        <el-form-item label="价格(分)"><el-input-number v-model="publishForm.priceCents" :min="0" :step="1000" /></el-form-item>
        <el-form-item label="授权">
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

    <!-- 明细 -->
    <el-drawer v-model="detailVisible" title="产品明细" size="56%">
      <div v-if="detail" class="credential">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="产品">{{ detail.product.title }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.product.templateLabel }}</el-descriptions-item>
          <el-descriptions-item label="整理出">{{ parseJson(detail.product.qualityJson, {}).groups }} 组</el-descriptions-item>
          <el-descriptions-item label="原始数据">{{ detail.product.rowCount }} 条</el-descriptions-item>
          <el-descriptions-item label="时间范围">{{ detail.product.coverageStart || '—' }} ~ {{ detail.product.coverageEnd || '—' }}</el-descriptions-item>
          <el-descriptions-item label="隐私保护">每组至少 {{ parseJson(detail.product.qualityJson, {}).kAnonymity }} 条</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">用了哪些文件</h4>
        <el-table :data="detail.items" size="small">
          <el-table-column prop="fileName" label="文件" min-width="160" />
          <el-table-column prop="parseMethod" label="读取方式" width="140" />
          <el-table-column prop="rowsContributed" label="贡献数据" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                {{ row.status === 1 ? '已使用' : '已排除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
        </el-table>

        <h4 class="section-title">产品内容预览</h4>
        <pre class="preview-text">{{ detail.product.sampleJson }}</pre>

        <h4 class="section-title">可复现凭证（技术信息）</h4>
        <p class="form-hint">用于证明产品与原料一一对应，买家可核对；不需要普通用户理解。</p>
        <pre class="preview-text">{{ detail.product.buildReceipt }}</pre>
      </div>
    </el-drawer>
  </main>
</template>
