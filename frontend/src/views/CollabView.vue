<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Share } from '@element-plus/icons-vue'
import { api } from '../api'

const sessions = ref([])
const folders = ref([])
const loading = ref(false)
const treeRef = ref(null)

// 发起
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ description: '', title: '', scopeFileIds: [] })
const createdResult = ref(null)
const createdVisible = ref(false)

// 加入
const joinVisible = ref(false)
const joinForm = reactive({ collabCode: '', collabPassword: '', scopeFileIds: [] })
const peeked = ref(null)
const peeking = ref(false)
const joinTreeRef = ref(null)

// 详情
const detailVisible = ref(false)
const detail = ref(null)
const addFilesVisible = ref(false)
const addForm = reactive({ scopeFileIds: [] })
const addTreeRef = ref(null)
const publishVisible = ref(false)
const publishForm = reactive({ title: '', summary: '', priceCents: 5000, licenseType: 'internal' })

const STATE = {
  0: { text: '协同中', type: 'primary' },
  1: { text: '已上架', type: 'success' },
  2: { text: '已结束', type: 'info' },
}

function stateOf(session) {
  return STATE[session?.state] || STATE[0]
}

function parseJson(text, fallback) {
  try { return JSON.parse(text) } catch { return fallback }
}

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
  sources: '贡献方数',
}

const treeData = computed(() => buildTree(folders.value))

function buildTree(rows) {
  const nodes = new Map()
  rows.forEach((row) => nodes.set(row.id, { id: row.id, label: row.name, childCount: row.childCount, children: [] }))
  const roots = []
  rows.forEach((row) => {
    const node = nodes.get(row.id)
    if (row.parentId && nodes.has(row.parentId)) nodes.get(row.parentId).children.push(node)
    else roots.push(node)
  })
  return roots
}

function previewCols(rows) {
  if (!Array.isArray(rows) || !rows.length) return []
  return Object.keys(rows[0]).map((k) => ({ key: k, label: MEASURE_LABELS[k] || k }))
}

async function load() {
  loading.value = true
  try {
    const [list, folderList] = await Promise.all([api.collabList(), api.sellerFolders()])
    sessions.value = list
    folders.value = folderList
  } finally {
    loading.value = false
  }
}

// ---------- 发起 ----------
function openCreate() {
  createForm.description = ''
  createForm.title = ''
  createForm.scopeFileIds = []
  createVisible.value = true
}

async function submitCreate() {
  if (createForm.description.trim().length < 4) {
    ElMessage.warning('用一句话说说这个协同要做什么，例如「把两边的通勤记录联合起来」')
    return
  }
  const checked = (treeRef.value && treeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选你要参与协同的文件夹')
    return
  }
  creating.value = true
  try {
    const result = await api.collabCreate({
      description: createForm.description.trim(),
      title: createForm.title.trim(),
      scopeFileIds: checked,
    })
    createVisible.value = false
    createdResult.value = result
    createdVisible.value = true
    await load()
  } finally {
    creating.value = false
  }
}

// ---------- 加入 ----------
function openJoin() {
  joinForm.collabCode = ''
  joinForm.collabPassword = ''
  joinForm.scopeFileIds = []
  peeked.value = null
  joinVisible.value = true
}

async function doPeek() {
  if (!joinForm.collabCode.trim() || !joinForm.collabPassword.trim()) {
    ElMessage.warning('请输入协同号和协同密码')
    return
  }
  peeking.value = true
  try {
    peeked.value = await api.collabPeek({
      collabCode: joinForm.collabCode.trim(),
      collabPassword: joinForm.collabPassword.trim(),
    })
  } catch (e) {
    peeked.value = null
  } finally {
    peeking.value = false
  }
}

async function submitJoin() {
  const checked = (joinTreeRef.value && joinTreeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选你要参与协同的文件夹')
    return
  }
  await api.collabJoin({
    collabCode: joinForm.collabCode.trim(),
    collabPassword: joinForm.collabPassword.trim(),
    scopeFileIds: checked,
  })
  ElMessage.success('已加入协同，产品规模已更新')
  joinVisible.value = false
  await load()
  const target = sessions.value.find((s) => s.collabCode === joinForm.collabCode.trim().toUpperCase())
  if (target) await openDetail(target)
}

// ---------- 详情 ----------
async function openDetail(session) {
  detail.value = await api.collabDetail(session.id)
  detailVisible.value = true
}

async function refreshDetail() {
  if (!detail.value) return
  detail.value = await api.collabDetail(detail.value.session.id)
  await load()
}

function openAddFiles() {
  addForm.scopeFileIds = []
  addFilesVisible.value = true
}

async function submitAddFiles() {
  const checked = (addTreeRef.value && addTreeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选新的文件夹')
    return
  }
  detail.value = await api.collabAddFiles(detail.value.session.id, { scopeFileIds: checked })
  addFilesVisible.value = false
  ElMessage.success('已更新你的贡献，产品规模已增大')
  await load()
}

function openPublish() {
  publishForm.title = detail.value.session.title
  publishForm.summary = '由 ' + detail.value.members.length + ' 位成员的 Agent 协同生成'
  publishForm.priceCents = 5000
  publishForm.licenseType = 'internal'
  publishVisible.value = true
}

async function submitPublish() {
  await ElMessageBox.confirm('上架后买方就能在市场看到这个协同产品，继续？', '上架协同产品', { type: 'warning' })
  const res = await api.collabPublish(detail.value.session.id, { ...publishForm })
  ElMessage.success('已上架为数据产品 #' + res.productId)
  publishVisible.value = false
  detailVisible.value = false
  await load()
}

async function closeCollab() {
  try {
    await ElMessageBox.confirm('结束后其他人将无法再加入，已上架的产品不受影响。继续？', '结束协同', { type: 'warning' })
    await api.collabClose(detail.value.session.id)
    detailVisible.value = false
    await load()
  } catch (e) { /* 取消 */ }
}

function copyText(text) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.warning('复制失败，请手动选择'),
  )
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>多 Agent 协同</h2>
        <p class="page-sub">
          用<strong>协同号</strong>把几个人的数据联合起来：每个人各自在自己网盘里整理，
          只把统计结果汇总，产品规模会随着成员加入而变大。<strong>原始文件始终不出各自的网盘。</strong>
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="Share" @click="openJoin">用协同号加入</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">发起协同</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="sessions" class="file-table">
      <el-table-column label="协同" min-width="220">
        <template #default="{ row }">
          <div class="listing-title">{{ row.title }}</div>
          <div class="listing-summary">{{ row.description || '（未填写说明）' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="协同号" width="120">
        <template #default="{ row }"><span class="mono">{{ row.collabCode }}</span></template>
      </el-table-column>
      <el-table-column prop="templateLabel" label="产品类型" width="130" />
      <el-table-column label="发起人" width="110" prop="ownerName" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag size="small" :type="stateOf(row).type" effect="plain">{{ stateOf(row).text }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-button text size="small" @click="openDetail(row)">进入</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !sessions.length" description="还没有协同，发起一个或用一个协同号加入" />

    <!-- 发起协同 -->
    <el-dialog v-model="createVisible" title="发起协同" width="680px">
      <el-form label-position="top">
        <el-form-item label="这个协同要做什么？">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="例如：把两边的通勤记录联合起来，看不同线路的平均耗时"
          />
        </el-form-item>
        <el-form-item label="选择你要参与的文件夹">
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
          <div class="form-hint">只读你勾选的文件；含身份证、手机号的文件会被自动排除。</div>
        </el-form-item>
        <el-form-item label="给它起个名字（可选）">
          <el-input v-model="createForm.title" placeholder="留空自动命名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建协同</el-button>
      </template>
    </el-dialog>

    <!-- 创建成功：显示协同号与密码 -->
    <el-dialog v-model="createdVisible" title="协同已创建" width="560px">
      <div v-if="createdResult" class="collab-credential">
        <p class="credential-hint">把下面的<strong>协同号</strong>和<strong>协同密码</strong>发给要一起做的人，他们凭这两项加入。</p>
        <div class="credential-row">
          <span class="credential-label">协同号</span>
          <span class="credential-value mono">{{ createdResult.collabCode }}</span>
          <el-button text size="small" @click="copyText(createdResult.collabCode)">复制</el-button>
        </div>
        <div class="credential-row">
          <span class="credential-label">协同密码</span>
          <span class="credential-value mono">{{ createdResult.collabPassword }}</span>
          <el-button text size="small" @click="copyText(createdResult.collabPassword)">复制</el-button>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="协同密码只显示这一次，请现在发给对方并妥善保存"
          style="margin-top: 14px"
        />
      </div>
      <template #footer>
        <el-button type="primary" @click="createdVisible = false">我知道了</el-button>
      </template>
    </el-dialog>

    <!-- 加入协同 -->
    <el-dialog v-model="joinVisible" title="用协同号加入" width="680px">
      <el-form label-position="top">
        <el-form-item label="协同号">
          <el-input v-model="joinForm.collabCode" placeholder="例如 A7K2M9QP" maxlength="16" @blur="joinForm.collabCode = joinForm.collabCode.toUpperCase()" />
        </el-form-item>
        <el-form-item label="协同密码">
          <el-input v-model="joinForm.collabPassword" placeholder="发起人发给你的 6 位密码" maxlength="32" />
        </el-form-item>
        <el-button :loading="peeking" @click="doPeek">查看协同内容</el-button>

        <div v-if="peeked" class="intent-box" style="margin-top: 16px">
          <div class="intent-head">
            <span class="intent-title">{{ peeked.title }}</span>
            <el-tag size="small" type="primary" effect="plain">{{ peeked.templateLabel }}</el-tag>
          </div>
          <p class="intent-reason">{{ peeked.description || peeked.templateDescription }}</p>
          <p class="intent-fields">
            发起人：{{ peeked.ownerName }} · 已有 {{ peeked.memberCount }} 人参与
            <span v-if="peeked.memberNames.length">（{{ peeked.memberNames.join('、') }}）</span>
          </p>
          <p class="intent-fields">会整理出：{{ peeked.fields.join('、') }}</p>
          <el-alert
            v-if="peeked.alreadyJoined"
            type="success"
            :closable="false"
            show-icon
            title="你已经在这次协同里了，可以在列表中进入查看"
          />
        </div>

        <template v-if="peeked && peeked.canJoin">
          <el-form-item label="选择你要参与的文件夹" style="margin-top: 16px">
            <div class="scope-box">
              <el-tree
                ref="joinTreeRef"
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
            <div class="form-hint">加入后你的统计会汇总进去，产品规模立即变大。</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="joinVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!peeked || !peeked.canJoin" @click="submitJoin">加入协同</el-button>
      </template>
    </el-dialog>

    <!-- 协同详情 -->
    <el-drawer v-model="detailVisible" :title="detail?.session?.title || '协同详情'" size="60%">
      <div v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="协同号"><span class="mono">{{ detail.session.collabCode }}</span></el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.session.ownerName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="stateOf(detail.session).type" effect="plain">{{ stateOf(detail.session).text }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="参与人数">{{ detail.members.length }}</el-descriptions-item>
          <el-descriptions-item label="整理出">{{ detail.totalGroups }} 组</el-descriptions-item>
          <el-descriptions-item label="产品类型">{{ detail.session.templateLabel }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">成员与贡献</h4>
        <el-table :data="detail.members" size="small">
          <el-table-column prop="userName" label="成员" width="120" />
          <el-table-column label="角色" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 2 ? 'warning' : 'info'" effect="plain">
                {{ row.role === 2 ? '发起人' : '成员' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fileCount" label="参与文件" width="100" />
          <el-table-column prop="rowsContributed" label="贡献组数" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 2 ? 'success' : 'info'" effect="plain">
                {{ row.status === 2 ? '已贡献' : '已加入' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="joinedAt" label="加入时间" min-width="150" />
        </el-table>

        <h4 class="section-title">联合结果（随成员加入而增大）</h4>
        <div v-if="detail.aggregate.length" class="preview-text">
          <el-table :data="detail.aggregate" size="small">
            <el-table-column
              v-for="col in previewCols(detail.aggregate)"
              :key="col.key"
              :label="col.label"
              min-width="110"
            >
              <template #default="{ row }">{{ row[col.key] }}</template>
            </el-table-column>
          </el-table>
        </div>
        <p v-else class="form-hint">还没有可用的聚合结果。</p>

        <div class="collab-actions">
          <el-button :icon="Refresh" @click="refreshDetail">刷新规模</el-button>
          <el-button @click="openAddFiles">增加我的文件夹</el-button>
          <el-button v-if="detail.ownerViewer" type="success" :disabled="!detail.canPublish" @click="openPublish">
            上架为数据产品
          </el-button>
          <el-button v-if="detail.ownerViewer" text type="danger" @click="closeCollab">结束协同</el-button>
        </div>
        <p v-if="!detail.ownerViewer" class="form-hint">只有发起人可以上架这个协同产品。</p>
      </div>
    </el-drawer>

    <!-- 追加文件夹 -->
    <el-dialog v-model="addFilesVisible" title="增加我的文件夹" width="600px">
      <el-tree
        ref="addTreeRef"
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
      <template #footer>
        <el-button @click="addFilesVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAddFiles">更新贡献</el-button>
      </template>
    </el-dialog>

    <!-- 上架 -->
    <el-dialog v-model="publishVisible" title="上架协同产品" width="560px">
      <el-form label-width="100px">
        <el-form-item label="标题"><el-input v-model="publishForm.title" /></el-form-item>
        <el-form-item label="介绍"><el-input v-model="publishForm.summary" type="textarea" :rows="3" /></el-form-item>
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
  </main>
</template>
