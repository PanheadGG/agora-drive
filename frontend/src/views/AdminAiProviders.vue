<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

const providers = ref([])
const status = ref(null)
const loading = ref(false)
const saving = ref(false)
const testingId = ref(0)
const dialogVisible = ref(false)
const editingId = ref(0)
const formRef = ref(null)

const form = reactive({
  provider: 'deepseek',
  name: '',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
  apiKey: '',
  priority: 10,
  status: 1,
  timeoutSeconds: 60,
})

const PRESETS = [
  { value: 'deepseek', label: 'DeepSeek 官方', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { value: 'qwen', label: '通义千问（阿里云百炼）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { value: 'zhipu', label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-plus' },
  { value: 'moonshot', label: '月之暗面 Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { value: 'custom', label: '自定义 / 自建网关', baseUrl: '', model: '' },
]

const rules = {
  provider: [{ required: true, message: '请选择平台', trigger: 'change' }],
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请填写 Base URL', trigger: 'blur' }],
  model: [{ required: true, message: '请填写模型名', trigger: 'blur' }],
  apiKey: [{
    validator: (rule, value, callback) => {
      if (!editingId.value && !value) callback(new Error('新增平台必须填写 API Key'))
      else callback()
    },
    trigger: 'blur',
  }],
}

const chainHint = computed(() => {
  if (!status.value) return ''
  if (!status.value.available) return '当前没有任何启用的模型平台，Agent 将使用本地规则判定'
  const active = status.value.chain.find((item) => item.id === status.value.activeProviderId)
  return '当前优先使用：' + (active ? active.name + '（' + active.model + '）' : '未知') +
    '；已自动切换 ' + status.value.failoverCount + ' 次'
})

async function load() {
  loading.value = true
  try {
    const [list, chainStatus] = await Promise.all([api.aiProviders(), api.aiProviderStatus()])
    providers.value = list
    status.value = chainStatus
  } finally {
    loading.value = false
  }
}

function applyPreset(value) {
  const preset = PRESETS.find((item) => item.value === value)
  if (!preset) return
  form.baseUrl = preset.baseUrl
  form.model = preset.model
  if (!form.name || PRESETS.some((item) => item.label === form.name)) form.name = preset.label
}

function openCreate() {
  editingId.value = 0
  form.provider = 'deepseek'
  form.name = 'DeepSeek 官方'
  form.baseUrl = 'https://api.deepseek.com'
  form.model = 'deepseek-chat'
  form.apiKey = ''
  form.priority = providers.value.length ? Math.max(...providers.value.map((p) => p.priority)) + 10 : 10
  form.status = 1
  form.timeoutSeconds = 60
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.provider = row.provider
  form.name = row.name
  form.baseUrl = row.baseUrl
  form.model = row.model
  form.apiKey = ''
  form.priority = row.priority
  form.status = row.status
  form.timeoutSeconds = row.timeoutSeconds
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = { ...form }
      if (editingId.value) await api.aiProviderUpdate(editingId.value, payload)
      else await api.aiProviderCreate(payload)
      ElMessage.success(editingId.value ? '已保存' : '已新增平台')
      dialogVisible.value = false
      await load()
    } finally {
      saving.value = false
    }
  })
}

async function testProvider(row) {
  testingId.value = row.id
  try {
    const result = await api.aiProviderTest(row.id)
    if (result.ok) ElMessage.success('连通正常，耗时 ' + result.latencyMs + ' ms')
    else ElMessage.error(result.message)
    await load()
  } finally {
    testingId.value = 0
  }
}

async function toggleStatus(row, enabled) {
  try {
    await api.aiProviderStatusToggle(row.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '已启用' : '已停用')
    await load()
  } catch {
    await load()
  }
}

async function move(row, delta) {
  const next = Math.max(0, row.priority + delta)
  await api.aiProviderPriority(row.id, next)
  await load()
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('删除「' + row.name + '」后其 API Key 密文也会一并删除，确定继续？', '删除平台', { type: 'warning' })
    await api.aiProviderDelete(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 取消 */
  }
}

function healthType(row) {
  if (row.lastStatus === 'ok') return 'success'
  if (row.lastStatus === 'failed') return 'danger'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div class="ai-provider-panel">
    <div class="page-head">
      <div>
        <h2>模型管理</h2>
        <p class="page-sub">
          维护多个自定义 AI 平台，按优先级依次尝试；某个平台超时或报错会自动切换到下一个。
          API Key 使用 AES-256-GCM 加密后入库，页面只显示脱敏串。
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增平台</el-button>
      </div>
    </div>

    <el-alert
      v-if="status"
      :type="status.available ? 'success' : 'warning'"
      :closable="false"
      show-icon
      class="chain-alert"
    >
      <template #title>{{ chainHint }}</template>
      <div class="chain-meta">
        <span>主密钥来源：{{ status.crypto.keySource === 'env' ? '环境变量 APP_MASTER_KEY' : status.crypto.keySource === 'file' ? '本地密钥文件' : '自动生成' }}</span>
        <span>算法：{{ status.crypto.algo }}</span>
        <span>密钥指纹：{{ status.crypto.keyFingerprint }}</span>
        <span>累计调用 {{ status.calls }} 次 / 失败 {{ status.failures }} 次</span>
        <span v-if="status.lastError" class="chain-error">最近错误：{{ status.lastError }}</span>
      </div>
    </el-alert>

    <el-table v-loading="loading" :data="providers" class="file-table">
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" @update:model-value="(value) => toggleStatus(row, value)" />
        </template>
      </el-table-column>
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <div class="provider-name">
            <span class="cell-text">{{ row.name }}</span>
            <el-tag v-if="row.active" size="small" type="success" effect="dark">当前使用</el-tag>
            <el-tag size="small" effect="plain">{{ row.provider }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="model" label="模型" width="150" />
      <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
      <el-table-column label="API Key" width="140">
        <template #default="{ row }">
          <span class="mono">{{ row.maskedKey }}</span>
          <div class="algo-tag">{{ row.encryptAlgo }}</div>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="130">
        <template #default="{ row }">
          <el-button text size="small" @click="move(row, -10)">↑</el-button>
          <span class="mono">{{ row.priority }}</span>
          <el-button text size="small" @click="move(row, 10)">↓</el-button>
        </template>
      </el-table-column>
      <el-table-column label="健康" width="180">
        <template #default="{ row }">
          <el-tag size="small" :type="healthType(row)">
            {{ row.lastStatus === 'ok' ? '正常' : row.lastStatus === 'failed' ? '失败' : '未检测' }}
          </el-tag>
          <div class="algo-tag">
            <span v-if="row.lastLatencyMs">{{ row.lastLatencyMs }} ms · </span>成功 {{ row.successCount }} / 失败 {{ row.failureCount }}
          </div>
          <div v-if="row.lastError" class="health-error" :title="row.lastError">{{ row.lastError.slice(0, 30) }}…</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" align="right">
        <template #default="{ row }">
          <el-button text size="small" :icon="Connection" :loading="testingId === row.id" @click="testProvider(row)">测试</el-button>
          <el-button text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button text size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !providers.length" description="还没有配置任何 AI 平台，点击右上角新增" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑平台' : '新增 AI 平台'" width="620px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="平台" prop="provider">
          <el-select v-model="form.provider" style="width: 100%" @change="applyPreset">
            <el-option v-for="item in PRESETS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="便于识别的名字，如「DeepSeek 主账号」" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="模型" prop="model">
          <el-input v-model="form.model" placeholder="deepseek-chat" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editingId ? '留空表示不修改，填写则轮换为新 Key' : '粘贴平台签发的 API Key'"
          />
          <div class="form-hint">Key 会以 AES-256-GCM 加密（密文 + 随机 IV + 校验标签）后入库，接口只返回脱敏串</div>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="9999" :step="10" />
          <span class="form-hint inline">数字越小越先用，失败自动切换下一个</span>
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number v-model="form.timeoutSeconds" :min="5" :max="600" :step="5" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chain-alert {
  margin-bottom: 16px;
}
.chain-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  margin-top: 4px;
  opacity: 0.85;
}
.chain-error {
  color: #f56c6c;
}
.provider-name {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
.algo-tag {
  font-size: 11px;
  color: var(--acd-text-2);
  margin-top: 2px;
}
.health-error {
  font-size: 11px;
  color: #f56c6c;
  margin-top: 2px;
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.form-hint {
  font-size: 12px;
  color: var(--acd-text-2);
  line-height: 1.6;
  margin-top: 4px;
}
.form-hint.inline {
  margin-left: 10px;
}
</style>
