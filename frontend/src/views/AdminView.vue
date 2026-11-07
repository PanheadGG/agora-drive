<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Monitor, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api'
import { auth } from '../store/auth'
import { theme, toggleTheme } from '../store/theme'
import { formatBytes, quotaPercent } from '../utils/format'
import AdminAiProviders from './AdminAiProviders.vue'

const router = useRouter()
const activeTab = ref('users')
const stats = ref({
  userCount: 0, fileCount: 0, folderCount: 0, totalUsed: 0, recycleCount: 0,
  bountyCount: 0, activeBountyCount: 0, scanTaskCount: 0, pendingReviewCount: 0,
})
const users = ref([])
const bounties = ref([])
const scanTasks = ref([])
const keyword = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [statsData, userList] = await Promise.all([api.adminStats(), api.adminUsers(keyword.value)])
    stats.value = statsData
    users.value = userList
  } finally {
    loading.value = false
  }
}

async function loadBounties() {
  bounties.value = await api.adminBounties()
}

async function loadScanTasks() {
  scanTasks.value = await api.adminScanTasks()
}

async function onTabChange(name) {
  if (name === 'bounties') await loadBounties()
  if (name === 'audit') await loadScanTasks()
}

async function toggleStatus(user, enabled) {
  try {
    await api.adminStatus(user.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '账号已启用' : '账号已禁用')
    await load()
  } catch {
    await load()
  }
}

async function editQuota(user) {
  const gb = Math.round(user.totalQuota / 1024 / 1024 / 1024)
  const input = window.prompt('为「' + user.username + '」设置配额（GB，当前 ' + gb + 'GB）', String(gb))
  if (input == null) return
  const value = Number(input)
  if (!Number.isFinite(value) || value <= 0) {
    ElMessage.warning('请输入正整数 GB 配额')
    return
  }
  await api.adminQuota(user.id, Math.round(value * 1024 * 1024 * 1024))
  ElMessage.success('配额已更新')
  await load()
}

async function setBountyStatus(bounty, status) {
  await api.adminBountyStatus(bounty.id, status)
  ElMessage.success('悬赏状态已更新')
  await loadBounties()
}

function logout() {
  auth.clear()
  router.replace('/login')
}

onMounted(load)
</script>

<template>
  <div class="admin-shell">
    <header class="topbar">
      <div class="brand" @click="router.push('/admin')">
        <span class="brand-mark">◍</span>
        <span class="brand-name">Agent 云盘 · 管理后台</span>
      </div>
      <div class="topbar-right">
        <el-button text :icon="Monitor" @click="router.push('/')">返回网盘</el-button>
        <el-button text circle :title="theme.dark ? '切换浅色' : '切换深色'" @click="toggleTheme">
          <el-icon><Sunny v-if="theme.dark" /><Moon v-else /></el-icon>
        </el-button>
        <el-button text @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="page">
      <div class="stat-grid">
        <el-card shadow="never" class="stat-card"><div class="stat-label">用户总数</div><div class="stat-value">{{ stats.userCount }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">文件数</div><div class="stat-value">{{ stats.fileCount }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">总占用空间</div><div class="stat-value">{{ formatBytes(stats.totalUsed) }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">悬赏（进行中 / 全部）</div><div class="stat-value">{{ stats.activeBountyCount }} / {{ stats.bountyCount }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">扫描任务</div><div class="stat-value">{{ stats.scanTaskCount }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">待复核匹配结果</div><div class="stat-value">{{ stats.pendingReviewCount }}</div></el-card>
      </div>

      <el-tabs v-model="activeTab" class="admin-tabs" @tab-change="onTabChange">
        <el-tab-pane label="用户管理" name="users">
          <div class="page-head">
            <h2>用户管理</h2>
            <div class="toolbar">
              <el-input v-model="keyword" placeholder="按用户名搜索" clearable style="width: 220px" @keyup.enter="load" @clear="load">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-button type="primary" :icon="Search" @click="load">搜索</el-button>
              <el-button :icon="Refresh" @click="load">刷新</el-button>
            </div>
          </div>

          <el-table v-loading="loading" :data="users" class="file-table">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="用户名" min-width="180">
              <template #default="{ row }">
                <span class="cell-text">{{ row.username }}</span>
                <el-tag v-if="row.role === 1" size="small" type="danger" effect="plain" style="margin-left: 8px">管理员</el-tag>
                <el-tag v-else-if="row.role === 2" size="small" type="warning" effect="plain" style="margin-left: 8px">企业需求方</el-tag>
                <el-tag v-else size="small" type="info" effect="plain" style="margin-left: 8px">个人用户</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="容量" min-width="240">
              <template #default="{ row }">
                <div class="quota-cell">
                  <el-progress :percentage="quotaPercent(row)" :stroke-width="6" :show-text="false" />
                  <span class="quota-cell-text">{{ formatBytes(row.usedSize) }} / {{ formatBytes(row.totalQuota) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.status === 1"
                  :disabled="row.role === 1"
                  @update:model-value="(value) => toggleStatus(row, value)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column label="操作" width="140" align="right">
              <template #default="{ row }">
                <el-button text size="small" @click="editQuota(row)">修改配额</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="悬赏管理" name="bounties">
          <div class="page-head">
            <h2>悬赏管理</h2>
            <div class="toolbar">
              <el-button :icon="Refresh" @click="loadBounties">刷新</el-button>
            </div>
          </div>
          <el-table :data="bounties" class="file-table">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="title" label="悬赏标题" min-width="240" />
            <el-table-column prop="publisherName" label="发布方" width="130" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'" effect="plain">{{ row.statusLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="scanTaskCount" label="扫描次数" width="100" />
            <el-table-column prop="highMatchCount" label="高匹配" width="90" />
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column label="操作" width="150" align="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 1" text size="small" @click="setBountyStatus(row, 2)">停用</el-button>
                <el-button v-else text size="small" @click="setBountyStatus(row, 1)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="模型管理" name="ai">
          <AdminAiProviders />
        </el-tab-pane>

        <el-tab-pane label="扫描审计" name="audit">
          <div class="page-head">
            <h2>Agent 扫描审计</h2>
            <div class="toolbar">
              <el-button :icon="Refresh" @click="loadScanTasks">刷新</el-button>
            </div>
          </div>
          <el-table :data="scanTasks" class="file-table">
            <el-table-column prop="id" label="任务" width="70" />
            <el-table-column prop="userName" label="发起用户" width="110" />
            <el-table-column prop="bountyTitle" label="悬赏" min-width="200" show-overflow-tooltip />
            <el-table-column prop="statusLabel" label="状态" width="90" />
            <el-table-column prop="totalFileCount" label="扫描文件" width="95" />
            <el-table-column prop="successParseCount" label="解析成功" width="95" />
            <el-table-column prop="failParseCount" label="解析失败" width="95" />
            <el-table-column prop="createdAt" label="创建时间" width="175" />
            <el-table-column prop="finishedAt" label="完成时间" width="175" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </main>
  </div>
</template>
