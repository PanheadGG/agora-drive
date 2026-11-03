<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Monitor, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api'
import { auth } from '../store/auth'
import { theme, toggleTheme } from '../store/theme'
import { formatBytes, quotaPercent } from '../utils/format'

const router = useRouter()
const stats = ref({ userCount: 0, fileCount: 0, folderCount: 0, totalUsed: 0, recycleCount: 0 })
const users = ref([])
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
  const input = window.prompt(`为「${user.username}」设置配额（GB，当前 ${gb}GB）`, String(gb))
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
        <el-card shadow="never" class="stat-card"><div class="stat-label">文件夹数</div><div class="stat-value">{{ stats.folderCount }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">总占用空间</div><div class="stat-value">{{ formatBytes(stats.totalUsed) }}</div></el-card>
        <el-card shadow="never" class="stat-card"><div class="stat-label">回收站条目</div><div class="stat-value">{{ stats.recycleCount }}</div></el-card>
      </div>

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
        <el-table-column label="用户名" min-width="160">
          <template #default="{ row }">
            <span class="cell-text">{{ row.username }}</span>
            <el-tag v-if="row.role === 1" size="small" type="warning" effect="plain" style="margin-left: 8px">管理员</el-tag>
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
        <el-table-column label="状态" width="120">
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
    </main>
  </div>
</template>
