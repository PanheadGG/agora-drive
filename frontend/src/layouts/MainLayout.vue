<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../store/auth'
import { disk, refreshQuota } from '../store/disk'
import { theme, toggleTheme } from '../store/theme'
import { formatBytes, quotaPercent } from '../utils/format'

const router = useRouter()
const route = useRoute()
const percent = computed(() => quotaPercent(disk.quota))
const current = computed(() => route.name)
const initial = computed(() => (auth.user?.username || '?').slice(0, 1).toUpperCase())

onMounted(() => {
  refreshQuota()
})

function onUserCommand(command) {
  if (command === 'recycle') router.push('/recycle')
  else if (command === 'admin') router.push('/admin')
  else if (command === 'logout') {
    auth.clear()
    router.replace('/login')
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand" @click="router.push('/')">
        <span class="brand-mark">◍</span>
        <span class="brand-name">Agent 云盘</span>
      </div>

      <nav class="topbar-nav">
        <a :class="['nav-link', { active: current === 'drive' || current === 'recycle' }]" @click="router.push('/')">我的文件</a>
        <a :class="['nav-link', { active: current === 'seller' }]" @click="router.push('/seller')">卖方工作台</a>
        <a :class="['nav-link', { active: current === 'market' }]" @click="router.push('/market')">数据市场</a>
        <a :class="['nav-link', { active: current === 'collab' }]" @click="router.push('/collab')">协同</a>
      </nav>

      <el-input v-model="disk.keyword" class="topbar-search" placeholder="搜索当前文件夹" clearable>
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <div class="topbar-right">
        <div class="quota">
          <el-progress
            :percentage="percent"
            :stroke-width="6"
            :show-text="false"
            :color="percent > 90 ? '#f56c6c' : '#1677ff'"
          />
          <span class="quota-text">
            {{ formatBytes(disk.quota.usedSize) }} / {{ formatBytes(disk.quota.totalQuota) }}
          </span>
        </div>

        <el-button text circle :title="theme.dark ? '切换浅色' : '切换深色'" @click="toggleTheme">
          <el-icon><Sunny v-if="theme.dark" /><Moon v-else /></el-icon>
        </el-button>

        <el-dropdown trigger="click" @command="onUserCommand">
          <div class="user-chip">
            <span class="avatar">{{ initial }}</span>
            <span class="user-name">{{ auth.user?.username }}</span>
            <el-tag size="small" effect="plain" :type="auth.isAdmin ? 'danger' : 'info'">
              {{ auth.isAdmin ? '管理员' : '买卖双向' }}
            </el-tag>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="recycle">回收站</el-dropdown-item>
              <el-dropdown-item v-if="auth.isAdmin" command="admin">管理后台</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <router-view />
  </div>
</template>
