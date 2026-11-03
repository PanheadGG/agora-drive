<script setup>
import { computed, onMounted, ref } from 'vue'
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

const currentBounty = computed(
  () => bounties.value.find((item) => item.id === selectedBounty.value) || null,
)

const LEVEL_TYPE = { 3: 'success', 2: 'primary', 1: 'info', 0: 'info' }

async function load() {
  loading.value = true
  try {
    const [bountyList, folderList, taskList] = await Promise.all([
      api.bountyList(1),
      api.agentFolders(),
      api.agentTasks(),
    ])
    bounties.value = bountyList
    folders.value = folderList
    tasks.value = taskList
    const fromQuery = Number(route.query.bountyId || 0)
    if (fromQuery && bountyList.some((item) => item.id === fromQuery)) selectedBounty.value = fromQuery
    else if (bountyList.length) selectedBounty.value = bountyList[0].id
  } finally {
    loading.value = false
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
  try {
    report.value = await api.agentScan({ bountyId: selectedBounty.value, scopeFileIds: checked })
    ElMessage.success('扫描完成，共处理 ' + report.value.totalFileCount + ' 个文件')
    tasks.value = await api.agentTasks()
  } finally {
    scanning.value = false
  }
}

async function openTask(id) {
  report.value = await api.agentTask(id)
}

async function review(item, value) {
  if (!report.value) return
  await api.agentReview(item.id, { review: value, note: value === 1 ? '确认可用，脱敏后交付' : '内容不采纳' })
  ElMessage.success(value === 1 ? '已标记为确认可用' : '已驳回')
  report.value = await api.agentTask(report.value.id)
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>智能匹配 Agent</h2>
        <p class="page-sub">先勾选授权扫描范围，Agent 会逐个打开文件解析真实内容，输出带证据与风险提示的推荐清单。</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="agent-layout" v-loading="loading">
      <el-card shadow="never" class="agent-panel">
        <div class="panel-title">1. 选悬赏</div>
        <el-select v-model="selectedBounty" placeholder="选择一条进行中的悬赏" style="width: 100%">
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

        <el-button
          type="primary"
          :icon="VideoPlay"
          :loading="scanning"
          class="scan-button"
          @click="startScan"
        >{{ scanning ? '正在解析文件内容…' : '开始扫描' }}</el-button>
      </el-card>

      <div class="agent-report">
        <el-card v-if="!report" shadow="never" class="agent-panel">
          <el-empty description="选择悬赏并授权范围后开始扫描，这里会显示分级推荐报告" />
        </el-card>

        <template v-else>
          <el-card shadow="never" class="agent-panel">
            <div class="report-head">
              <div>
                <span class="report-title">{{ report.bountyTitle }}</span>
                <el-tag size="small" type="success" effect="plain" style="margin-left: 8px">{{ report.statusLabel }}</el-tag>
              </div>
              <span class="panel-note">任务 #{{ report.id }} · {{ report.createdAt }}</span>
            </div>

            <div class="stat-row">
              <div class="stat-item"><span class="stat-num">{{ report.totalFileCount }}</span><span>授权范围文件</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.successParseCount }}</span><span>成功解析</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.failParseCount }}</span><span>未能解析</span></div>
              <div class="stat-item"><span class="stat-num">{{ report.recommendations.length }}</span><span>进入推荐</span></div>
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
              <el-table-column prop="fileName" label="文件" min-width="180" />
              <el-table-column prop="parseMethod" label="解析方式" width="140" />
              <el-table-column prop="parsedSummary" label="解析出的内容要点" min-width="300" show-overflow-tooltip />
            </el-table>
          </el-card>

          <el-card shadow="never" class="agent-panel">
            <h4>四、推荐清单（分级）</h4>
            <div v-if="!report.recommendations.length" class="panel-note">本次没有达到匹配门槛的文件。</div>
            <div v-for="item in report.recommendations" :key="item.id" class="rec-item">
              <div class="rec-head">
                <el-tag size="small" :type="LEVEL_TYPE[item.matchLevel]" effect="dark">{{ item.matchLabel }}</el-tag>
                <span class="rec-name">{{ item.fileName }}</span>
                <span class="rec-size">{{ formatBytes(item.fileSize) }}</span>
                <div class="rec-actions">
                  <el-button size="small" :type="item.userReview === 1 ? 'success' : 'default'" @click="review(item, 1)">确认可用</el-button>
                  <el-button size="small" text @click="review(item, 2)">驳回</el-button>
                  <el-tag v-if="item.userReview === 1" size="small" type="success" effect="plain">已确认</el-tag>
                  <el-tag v-else-if="item.userReview === 2" size="small" type="info" effect="plain">已驳回</el-tag>
                </div>
              </div>
              <p class="rec-evidence"><span class="label">证据：</span>{{ item.evidenceText || '（未命中关键词，仅供参考）' }}</p>
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
            </div>
            <div v-if="report.skipped.length" class="skipped-block">
              <div class="panel-note">本次未能解析（如实说明，未编造结论）：</div>
              <div v-for="item in report.skipped" :key="item.id" class="skipped-item">
                <span class="rec-name">{{ item.fileName }}</span>
                <span class="report-text">{{ item.parsedSummary }}</span>
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
        <el-table-column prop="id" label="任务" width="80" />
        <el-table-column prop="bountyTitle" label="悬赏" min-width="220" />
        <el-table-column prop="statusLabel" label="状态" width="100" />
        <el-table-column prop="totalFileCount" label="文件数" width="90" />
        <el-table-column prop="successParseCount" label="解析成功" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button text size="small" @click="openTask(row.id)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </main>
</template>
