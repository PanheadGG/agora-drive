<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'
import { refreshQuota } from '../store/disk'
import { formatBytes, iconOf, kindClass } from '../utils/format'

const router = useRouter()
const items = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    items.value = await api.recycle()
  } finally {
    loading.value = false
  }
}

async function restore(file) {
  await api.restore(file.id)
  ElMessage.success('已恢复')
  await load()
}

async function purge(file) {
  try {
    await ElMessageBox.confirm(`彻底删除「${file.name}」？此操作不可恢复。`, '彻底删除', { type: 'warning' })
    await api.purge(file.id)
    ElMessage.success('已彻底删除')
    await Promise.all([load(), refreshQuota()])
  } catch {
    /* 取消 */
  }
}

async function emptyRecycle() {
  try {
    await ElMessageBox.confirm('清空回收站会释放全部占用空间，确定继续？', '清空回收站', { type: 'warning' })
    await api.emptyRecycle()
    ElMessage.success('回收站已清空')
    await Promise.all([load(), refreshQuota()])
  } catch {
    /* 取消 */
  }
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div class="page-title">
        <el-button text :icon="ArrowLeft" @click="router.push('/')">返回我的文件</el-button>
        <h2>回收站</h2>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="danger" plain :disabled="!items.length" @click="emptyRecycle">清空回收站</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="items" class="file-table">
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <div class="cell-name">
            <el-icon :size="18" :class="['cell-icon', kindClass(row)]"><component :is="iconOf(row)" /></el-icon>
            <span class="cell-text">{{ row.name }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">{{ row.folder ? '—' : formatBytes(row.size) }}</template>
      </el-table-column>
      <el-table-column label="删除时间" width="180" prop="updatedAt" />
      <el-table-column label="操作" width="200" align="right">
        <template #default="{ row }">
          <el-button text size="small" @click="restore(row)">恢复</el-button>
          <el-button text size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !items.length" description="回收站是空的" />
  </main>
</template>
