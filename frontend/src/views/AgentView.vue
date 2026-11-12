<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { api } from '../api'
import { formatBytes } from '../utils/format'

const route = useRoute()
const bounties = ref([])
const folders = ref([])
const tasks = ref([])
const selectedBounty = ref(null)
const treeRef = ref(null)
const scanning = ref(false)
const loading = ref(false)
const report = ref(null)
const llmStatus = ref({ llm: { available: false }, ocr: { available: null } })

const progress = ref(null)
const progressEvents = ref([])
const showTimeline = ref(false)
let pollTimer = null

const aiEnabled = ref(false)
const aiToggleTouched = ref(false)
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewData = ref(null)
const previewImageUrl = ref('')

const llmAvailable = computed(() => !!(llmStatus.value.llm && llmStatus.value.llm.available))

const llmHint = computed(() => {
  const llm = llmStatus.value.llm || {}
  const ocr = llmStatus.value.ocr || {}
  const vision = llmStatus.value.vision || {}
  const llmText = llm.available
    ? '大模型可用（' + (llm.model || 'deepseek-chat') + '），需在上面手动打开才会参与判定'
    : '未配置可用模型平台，只能使用本地规则判定'
  const visionText = vision.available ? '；识图判定可用' : '；识图判定不可用'
  const ocrText = ocr.available === false ? '；图片 OCR 不可用' : ''
  return llmText + visionText + ocrText
})

/** 未开启大模型判定时的等级说明，用于列表标签 */
function levelTag(item) {
  if (item.matchLevel === 3) return { text: '高匹配', type: 'success' }
  if (item.matchLevel === 2) return { text: item.levelCapped ? '可能高匹配' : '中匹配', type: item.levelCapped ? 'warning' : 'primary' }
  if (item.matchLevel === 1) return { text: '低匹配', type: 'info' }
  return { text: '不推荐', type: 'info' }
}

const treeData = computed(() => {
  const nodes = new Map()
  folders.value.forEach((row) => {
    nodes.set(row.id, { id: row.id, label: row.name, childCount: row.childCount, children: [] })
  })
  const roots = []
  folders.value.forEach((row) => {
    const node = nodes.get(row.id)
    if (row.parentId && nodes.has(row.parentId)) nodes.get(row.parentId).children.push(node)
    else roots.push(node)
  })
  return roots
})

const currentBounty = computed(() => bounties.value.find((item) => item.id === selectedBounty.value) || null)
const LEVEL_TYPE = { 3: 'success', 2: 'primary', 1: 'info', 0: 'info' }

async function load() {
  loading.value = true
  try {
    const [bountyList, folderList, taskList, status] = await Promise.all([
      api.bountyList(1),
      api.agentFolders(),
      api.agentTasks(),
      api.agentLlmStatus(),
    ])
    bounties.value = bountyList
    folders.value = folderList
    tasks.value = taskList
    llmStatus.value = status
    const fromQuery = Number(route.query.bountyId || 0)
    if (fromQuery && bountyList.some((item) => item.id === fromQuery)) selectedBounty.value = fromQuery
    else if (bountyList.length) selectedBounty.value = bountyList[0].id
  } finally {
    loading.value = false
  }
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function poll(taskId) {
  try {
    const data = await api.agentProgress(taskId)
    progress.value = data
    if (data.events && data.events.length) progressEvents.value = data.events
    if (data.done) {
      stopPolling()
      scanning.value = false
      if (data.taskStatus === 2) {
        report.value = await api.agentTask(taskId)
        tasks.value = await api.agentTasks()
        ElMessage.success('扫描完成，共处理 ' + data.total + ' 个文件')
      } else {
        ElMessage.error('扫描失败：' + (data.message || '未知错误'))
      }
      return
    }
    pollTimer = setTimeout(() => poll(taskId), 400)
  } catch (error) {
    stopPolling()
    scanning.value = false
  }
}

async function startScan() {
  if (!selectedBounty.value) {
    ElMessage.warning('请先选择一条悬赏')
    return
  }
  const checked = (treeRef.value && treeRef.value.getCheckedKeys()) || []
  if (!checked.length) {
    ElMessage.warning('请勾选授权扫描的文件夹（Agent 只会扫描勾选范围）')
    return
  }
  scanning.value = true
  report.value = null
  progress.value = null
  progressEvents.value = []
  showTimeline.value = false
  try {
    const started = await api.agentScan({
      bountyId: selectedBounty.value,
      scopeFileIds: checked,
      aiEnabled: aiEnabled.value && llmAvailable.value,
    })
    progress.value = { taskId: started.taskId, taskStatus: 1, stage: '排队中', total: started.totalFileCount, processed: 0, percent: 0, events: [], done: false }
    poll(started.taskId)
  } catch (error) {
    scanning.value = false
  }
}

async function openTask(id) {
  report.value = await api.agentTask(id)
  progress.value = null
  progressEvents.value = []
}

async function review(item, value) {
  if (!report.value) return
  await api.agentReview(item.id, { review: value, note: value === 1 ? '确认可用，脱敏后交付' : '内容不采纳' })
  ElMessage.success(value === 1 ? '已标记为确认可用' : '已驳回')
  report.value = await api.agentTask(report.value.id)
}

function clearPreviewUrl() {
  if (previewImageUrl.value) {
    URL.revokeObjectURL(previewImageUrl.value)
    previewImageUrl.value = ''
  }
}

async function openPreview(item) {
  previewVisible.value = true
  previewLoading.value = true
  previewData.value = null
  clearPreviewUrl()
  try {
    const data = await api.agentPreview(item.id)
    previewData.value = data
    if (data.kind === 'image') {
      const response = await api.content(data.fileId)
      previewImageUrl.value = URL.createObjectURL(response.data)
    }
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  clearPreviewUrl()
  previewData.value = null
}

function eventType(event) {
  if (event.riskTag) return 'danger'
  if (!event.ok) return 'warning'
  return 'success'
}

onMounted(load)
onBeforeUnmount(stopPolling)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>智能匹配 Agent</h2>
        <p class="page-sub">先勾选授权扫描范围，Agent 会逐个流式解析文件内容（表格走 EasyExcel 逐行、文档边解析边检测），输出带证据与风险提示的推荐清单。</p>
        <p class="llm-hint">{{ llmHint }}</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="agent-layout" v-loading="loading">
      <el-card shadow="never" class="agent-panel">
        <div class="panel-title">1. 选悬赏</div>
        <el-select v-model="selectedBounty" placeholder="选择一条进行中的悬赏" style="width: 100%" :disabled="scanning">
          <el-option v-for="bounty in bounties" :key="bounty.id" :label="bounty.title" :value="bounty.id" />
        </el-select>
        <p v-if="currentBounty" class="panel-note">{{ currentBounty.rawDesc }}</p>

        <el-divider />

        <div class="panel-title">2. 授权扫描范围</div>
        <el-tree
          ref="treeRef"
          :data="treeData"
          show-checkbox
          node-key="id"
          default-expand-all
          :props="{ label: 'label', children: 'children' }"
          class="scope-tree"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <el-icon><Folder /></el-icon>
              <span>{{ data.label }}</span>
              <span class="tree-count">{{ data.childCount }} 项</span>
            </span>
          </template>
        </el-tree>
        <p class="panel-note">未勾选的文件夹不会被访问；范围外的文件一律不评估。</p>

        <el-divider />

        <div class="panel-title">3. 大模型判定（可选）</div>
        <div class="ai-switch-row">
          <el-switch v-model="aiEnabled" :disabled="!llmAvailable || scanning" @change="aiToggleTouched = true" />
          <div class="ai-switch-text">
            <span class="ai-switch-title">{{ aiEnabled ? '已开启：允许大模型判定' : '未开启：只用本地规则' }}</span>
            <span class="ai-switch-sub">{{ aiEnabled ? '会调用大模型做语义判定，并让视觉模型看图片画面' : '不开启时最高只能标为「可能高匹配」，且不做识图' }}</span>
          </div>
        </div>
        <el-alert
          v-if="!llmAvailable"
          type="warning"
          :closable="false"
          show-icon
          title="当前没有可用的大模型平台，开关不可用，结果一律按本地规则并降级为「可能高匹配」"
          class="ai-warning"
        />

        <el-button
          type="primary"
          :icon="VideoPlay"
          :loading="scanning"
          class="scan-button"
          :disabled="scanning"
          @click="startScan"
        >{{ scanning ? '正在扫描…' : '开始扫描' }}</el-button>
      </el-card>

      <div class="agent-report">
        <!-- 扫描过程：实时进度 -->
        <el-card v-if="scanning || (progress && !report)" shadow="never" class="agent-panel">
          <div class="report-head">
            <span class="report-title">正在扫描</span>
            <span class="panel-note" v-if="progress">任务 #{{ progress.taskId }} · 已用时 {{ ((progress.elapsedMs || 0) / 1000).toFixed(1) }}s</span>
          </div>

          <div class="scan-stage">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ progress?.stage || '准备中…' }}</span>
            <span v-if="progress?.currentFile" class="scan-current">当前：{{ progress.currentFile }}</span>
          </div>

          <el-progress
            :percentage="progress?.percent || 0"
            :stroke-width="10"
            :status="progress?.taskStatus === 3 ? 'exception' : undefined"
          />
          <div class="scan-stats">
            <span>已处理 {{ progress?.processed || 0 }} / {{ progress?.total || 0 }}</span>
            <span>解析成功 {{ progress?.success || 0 }}</span>
            <span>跳过 {{ progress?.fail || 0 }}</span>
          </div>

          <div class="scan-events">
            <div v-for="event in progressEvents.slice().reverse()" :key="event.seq" class="scan-event">
              <el-tag size="small" :type="eventType(event)" effect="plain">{{ event.stage }}</el-tag>
              <span class="scan-event-name">{{ event.fileName }}</span>
              <span class="scan-event-method">{{ event.parseMethod }}</span>
              <span v-if="event.riskTag" class="scan-event-risk">{{ event.riskTag }}</span>
              <span class="scan-event-detail">{{ event.detail }}</span>
            </div>
            <div v-if="!progressEvents.length" class="panel-note">正在读取文件与上一阶段的解析结果…</div>
          </div>
        </el-card>

        <el-card v-if="!scanning && !report" shadow="never" class="agent-panel">
          <el-empty description="选择悬赏并授权范围后开始扫描，这里会实时显示解析过程与分级推荐报告" />
        </el-card>

        <template v-if="report && !scanning">
          <el-card shadow="never" class="agent-panel">
            <div class="report-head">
              <div>
                <span class="report-title">{{ report.bountyTitle }}</span>
                <el-tag size="small" type="success" effect="plain" style="margin-left: 8px">{{ report.statusLabel }}</el-tag>
              </div>
              <div class="report-head-right">
                <span class="panel-note">任务 #{{ report.id }} · {{ report.createdAt }}</span>
                <el-button v-if="progressEvents.length" text size="small" @click="showTimeline = !showTimeline">
                  {{ showTimeline ? '收起扫描过程' : '查看扫描过程' }}
                </el-button>
              </div>
            </div>

            <el-collapse-transition>
              <div v-show="showTimeline" class="scan-events">
                <div v-for="event in progressEvents" :key="event.seq" class="scan-event">
                  <el-tag size="small" :type="eventType(event)" effect="plain">{{ event.stage }}</el-tag>
                  <span class="scan-event-name">{{ event.fileName }}</span>
                  <span class="scan-event-method">{{ event.parseMethod }}</span>
                  <span v-if="event.riskTag" class="scan-event-risk">{{ event.riskTag }}</span>
                </div>
              </div>
            </el-collapse-transition>

            <div class="report-notices">
              <el-alert
                v-for="(notice, index) in report.notices"
                :key="index"
                :type="report.aiEnabled ? 'info' : 'warning'"
                :closable="false"
                show-icon
                :title="notice"
                class="report-notice"
              />
            </div>
            <p class="llm-hint" style="margin-top: 8px">
              {{ report.aiEnabled
                ? ('本次已开启大模型判定：' + report.llmJudgedCount + ' 个文件语义判定，' + report.visionUsedCount + ' 张图片识图（' + (report.llmModel || 'deepseek-chat') + '）')
                : '本次未开启大模型判定，仅本地规则，最高等级为「可能高匹配」' }}
            </p>

            <div class="stat-row">
              <div class="stat-item"><span class="stat-num">{{ report.totalFileCount }}</span><span>授权范围文件</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.successParseCount }}</span><span>成功解析</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.failParseCount }}</span><span>未能解析</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.recommendations.length }}</span><span>进入推荐</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.possibleHighCount }}</span><span>其中可能高匹配</span></div>
            </div>

            <div class="report-section">
              <h4>一、悬赏需求理解</h4>
              <p class="report-text">{{ report.understanding }}</p>
              <div class="chip-row">
                <el-tag v-for="word in report.excludeKeywords" :key="word" size="small" type="info" effect="plain">排除：{{ word }}</el-tag>
              </div>
            </div>

            <div class="report-section">
              <h4>二、范围与粗筛结果</h4>
              <p class="report-text">{{ report.authorityNote }}</p>
            </div>
          </el-card>

          <el-card shadow="never" class="agent-panel">
            <h4>三、已实际解析的文件清单</h4>
            <el-table :data="report.parsedList" size="small" class="report-table">
              <el-table-column prop="fileName" label="文件" min-width="170" />
              <el-table-column prop="parseMethod" label="解析方式" width="150" />
              <el-table-column prop="parsedSummary" label="解析出的内容要点" min-width="240" show-overflow-tooltip />
              <el-table-column label="等级" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="levelTag(row).type" effect="plain">{{ levelTag(row).text }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="判定来源" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.judgeSource === 'llm' ? 'success' : 'info'" effect="plain">
                    {{ row.judgeSource === 'llm' ? '大模型' : '本地规则' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button text size="small" @click="openPreview(row)">预览</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never" class="agent-panel">
            <h4>四、推荐清单（分级）</h4>
            <div v-if="!report.recommendations.length" class="panel-note">本次没有达到匹配门槛的文件。</div>
            <div v-for="item in report.recommendations" :key="item.id" class="rec-item">
              <div class="rec-head">
                <el-tag size="small" :type="levelTag(item).type" effect="dark">{{ levelTag(item).text }}</el-tag>
                <el-tag v-if="item.visionOk === true" size="small" type="success" effect="plain">识图通过</el-tag>
                <el-tag v-else-if="item.visionOk === false" size="small" type="danger" effect="plain">画面无关</el-tag>
                <span class="rec-name">{{ item.fileName }}</span>
                <span class="rec-size">{{ formatBytes(item.fileSize) }}</span>
                <el-tag size="small" :type="item.judgeSource === 'llm' ? 'success' : 'info'" effect="plain">
                  {{ item.judgeSource === 'llm' ? '大模型' : '本地规则' }}
                </el-tag>
                <div class="rec-actions">
                  <el-button size="small" text @click="openPreview(item)">预览</el-button>
                  <el-button size="small" :type="item.userReview === 1 ? 'success' : 'default'" @click="review(item, 1)">确认可用</el-button>
                  <el-button size="small" text @click="review(item, 2)">驳回</el-button>
                  <el-tag v-if="item.userReview === 1" size="small" type="success" effect="plain">已确认</el-tag>
                  <el-tag v-else-if="item.userReview === 2" size="small" type="info" effect="plain">已驳回</el-tag>
                </div>
              </div>
              <p class="rec-evidence"><span class="label">证据：</span>{{ item.evidenceText || '（未命中关键词，仅供参考）' }}</p>
              <p class="rec-evidence" v-if="item.visionScene"><span class="label">画面：</span>{{ item.visionScene }}</p>
              <p class="rec-risk" v-if="item.riskTag"><span class="label">风险：</span>{{ item.riskTag }} — {{ item.riskDesc }}</p>
            </div>
          </el-card>

          <el-card shadow="never" class="agent-panel">
            <h4>五、高风险与排除说明</h4>
            <div v-if="!report.highRisk.length" class="panel-note">未发现敏感信息或网络搬运内容。</div>
            <div v-for="item in report.highRisk" :key="item.id" class="risk-item">
              <el-tag size="small" type="danger" effect="plain">{{ item.riskTag }}</el-tag>
              <span class="rec-name">{{ item.fileName }}</span>
              <span class="report-text">{{ item.riskDesc }}</span>
              <span v-if="item.visionScene" class="report-text">（画面：{{ item.visionScene }}）</span>
              <el-button text size="small" @click="openPreview(item)">预览</el-button>
            </div>
            <div v-if="report.skipped.length" class="skipped-block">
              <div class="panel-note">本次未能解析（如实说明，未编造结论）：</div>
              <div v-for="item in report.skipped" :key="item.id" class="skipped-item">
                <span class="rec-name">{{ item.fileName }}</span>
                <span class="report-text">{{ item.parsedSummary }}</span>
                <el-button text size="small" @click="openPreview(item)">预览</el-button>
              </div>
            </div>
          </el-card>

          <el-card shadow="never" class="agent-panel">
            <h4>六、人工复核提醒</h4>
            <ul class="tips-list">
              <li v-for="(tip, index) in report.reviewTips" :key="index">{{ tip }}</li>
            </ul>
          </el-card>
        </template>
      </div>
    </div>

    <el-card shadow="never" class="agent-panel history-panel">
      <h4>我的扫描记录</h4>
      <el-table :data="tasks" size="small" class="report-table">
        <el-table-column prop="id" label="任务" width="70" />
        <el-table-column prop="bountyTitle" label="悬赏" min-width="220" />
        <el-table-column prop="statusLabel" label="状态" width="90" />
        <el-table-column prop="totalFileCount" label="文件数" width="80" />
        <el-table-column prop="successParseCount" label="解析成功" width="90" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button text size="small" @click="openTask(row.id)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="previewVisible" :title="previewData?.fileName || '内容预览'" size="56%" @closed="closePreview">
      <div v-loading="previewLoading" class="preview-pane">
        <template v-if="previewData">
          <div class="preview-meta">
            <el-tag size="small" effect="plain">{{ previewData.parseMethod || '未解析' }}</el-tag>
            <el-tag v-if="previewData.riskTag" size="small" type="danger" effect="plain">{{ previewData.riskTag }}</el-tag>
            <span class="preview-risk" v-if="previewData.riskDesc">{{ previewData.riskDesc }}</span>
          </div>

          <img v-if="previewData.kind === 'image' && previewImageUrl" :src="previewImageUrl" class="preview-image" :alt="previewData.fileName" />

          <el-table v-else-if="previewData.kind === 'table'" :data="previewData.rows" size="small" border class="preview-table" max-height="62vh">
            <el-table-column
              v-for="(title, index) in previewData.header"
              :key="index"
              :label="title || ('列 ' + (index + 1))"
              min-width="120"
              show-overflow-tooltip
            >
              <template #default="{ row }">{{ row[index] }}</template>
            </el-table-column>
          </el-table>

          <pre v-else class="preview-text">{{ previewData.text || '（无可预览文本）' }}</pre>

          <p v-if="previewData.truncated" class="panel-note">内容较长，预览已截断。</p>
        </template>
      </div>
    </el-drawer>
  </main>
</template>
