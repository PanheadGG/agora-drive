<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, FolderAdd, MoreFilled, Refresh, Upload, View } from '@element-plus/icons-vue'
import { api } from '../api'
import { disk, refreshQuota, setView } from '../store/disk'
import { formatBytes, iconOf, kindClass } from '../utils/format'
import { downloadBlob, uploadFile } from '../utils/upload'

const parentId = ref(null)
const breadcrumb = ref([])
const items = ref([])
const loading = ref(false)
const uploads = ref([])
const fileInput = ref(null)

const previewVisible = ref(false)
const previewFile = ref(null)
const previewLoading = ref(false)
const previewText = ref('')
const previewUrl = ref('')

async function load() {
  loading.value = true
  try {
    const data = await api.list(parentId.value, disk.keyword)
    items.value = data.items
    breadcrumb.value = data.breadcrumb
  } finally {
    loading.value = false
  }
}

function open(id) {
  parentId.value = id ?? null
  load()
}

function enter(file) {
  if (file.folder) open(file.id)
}

function pickFiles() {
  fileInput.value?.click()
}

async function onFilesSelected(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  for (const file of files) {
    const task = reactive({ name: file.name, percent: 0, status: 'uploading' })
    uploads.value.push(task)
    try {
      await uploadFile(file, parentId.value, (percent) => {
        task.percent = percent
      })
      task.status = 'done'
    } catch {
      task.status = 'error'
    }
  }
  await Promise.all([load(), refreshQuota()])
  setTimeout(() => {
    uploads.value = uploads.value.filter((task) => task.status === 'uploading')
  }, 4000)
}

async function createFolder() {
  try {
    const { value } = await ElMessageBox.prompt('请输入文件夹名称', '新建文件夹', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    await api.createFolder({ parentId: parentId.value, name: value.trim() })
    ElMessage.success('已创建')
    await load()
  } catch {
    /* 取消 */
  }
}

async function onCommand(command, file) {
  if (command === 'rename') {
    try {
      const { value } = await ElMessageBox.prompt('请输入新名称', '重命名', {
        inputValue: file.name,
        inputPattern: /\S+/,
        inputErrorMessage: '名称不能为空',
      })
      await api.rename(file.id, value.trim())
      await load()
    } catch {
      /* 取消 */
    }
  } else if (command === 'delete') {
    try {
      await ElMessageBox.confirm(`将「${file.name}」移入回收站？`, '删除', { type: 'warning' })
      await api.remove(file.id)
      ElMessage.success('已移入回收站')
      await Promise.all([load(), refreshQuota()])
    } catch {
      /* 取消 */
    }
  }
}

async function download(file) {
  const response = await api.content(file.id, true)
  downloadBlob(response.data, file.name)
}

function clearPreviewUrl() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

async function preview(file) {
  previewFile.value = file
  previewVisible.value = true
  previewLoading.value = true
  previewText.value = ''
  clearPreviewUrl()
  try {
    const response = await api.content(file.id)
    if (file.kind === 'image') previewUrl.value = URL.createObjectURL(response.data)
    else previewText.value = await response.data.text()
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  clearPreviewUrl()
  previewFile.value = null
  previewText.value = ''
}

watch(() => disk.keyword, load)
onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item>
          <a class="crumb" @click="open(null)">我的文件</a>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-for="item in breadcrumb" :key="item.id">
          <a class="crumb" @click="open(item.id)">{{ item.name }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>

      <div class="toolbar">
        <el-button :icon="FolderAdd" @click="createFolder">新建文件夹</el-button>
        <el-button type="primary" :icon="Upload" @click="pickFiles">上传</el-button>
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-radio-group :model-value="disk.view" size="small" @update:model-value="setView">
          <el-radio-button value="grid">宫格</el-radio-button>
          <el-radio-button value="list">列表</el-radio-button>
        </el-radio-group>
        <input ref="fileInput" type="file" multiple hidden @change="onFilesSelected" />
      </div>
    </div>

    <div v-loading="loading" class="file-area">
      <template v-if="items.length">
        <div v-if="disk.view === 'grid'" class="file-grid">
          <div v-for="file in items" :key="file.id" class="file-card" @dblclick="enter(file)">
            <div class="file-icon" :class="kindClass(file)">
              <el-icon :size="30"><component :is="iconOf(file)" /></el-icon>
            </div>
            <div class="file-name" :title="file.name">{{ file.name }}</div>
            <div class="file-meta">{{ file.folder ? '文件夹' : formatBytes(file.size) }}</div>
            <div class="file-actions">
              <el-button v-if="file.previewable" text size="small" :icon="View" @click.stop="preview(file)">预览</el-button>
              <el-button v-if="!file.folder" text size="small" :icon="Download" @click.stop="download(file)">下载</el-button>
              <el-dropdown trigger="click" @command="(command) => onCommand(command, file)">
                <el-button text size="small" :icon="MoreFilled" @click.stop />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </div>

        <el-table v-else :data="items" class="file-table" @row-dblclick="enter">
          <el-table-column label="名称" min-width="320">
            <template #default="{ row }">
              <div class="cell-name">
                <el-icon :size="18" :class="['cell-icon', kindClass(row)]"><component :is="iconOf(row)" /></el-icon>
                <span class="cell-text" :title="row.name">{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="120">
            <template #default="{ row }">{{ row.folder ? '—' : formatBytes(row.size) }}</template>
          </el-table-column>
          <el-table-column label="修改时间" width="180" prop="updatedAt" />
          <el-table-column label="操作" width="240" align="right">
            <template #default="{ row }">
              <el-button v-if="row.previewable" text size="small" @click="preview(row)">预览</el-button>
              <el-button v-if="!row.folder" text size="small" @click="download(row)">下载</el-button>
              <el-dropdown trigger="click" @command="(command) => onCommand(command, row)">
                <el-button text size="small" :icon="MoreFilled" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <el-empty v-else :description="disk.keyword ? '没有匹配的文件' : '这里还空着，上传第一个文件吧'" />
    </div>

    <transition name="el-fade-in">
      <div v-if="uploads.length" class="upload-panel">
        <div class="upload-panel-head">上传任务</div>
        <div v-for="(task, index) in uploads" :key="index" class="upload-item">
          <div class="upload-item-name">{{ task.name }}</div>
          <el-progress
            :percentage="task.percent"
            :status="task.status === 'error' ? 'exception' : task.status === 'done' ? 'success' : undefined"
            :stroke-width="6"
          />
        </div>
      </div>
    </transition>

    <el-dialog
      v-model="previewVisible"
      :title="previewFile?.name"
      width="70%"
      top="6vh"
      destroy-on-close
      @closed="closePreview"
    >
      <div v-loading="previewLoading" class="preview-body">
        <img v-if="previewFile?.kind === 'image' && previewUrl" :src="previewUrl" class="preview-image" :alt="previewFile?.name" />
        <pre v-else-if="previewFile?.kind === 'text'" class="preview-text">{{ previewText }}</pre>
      </div>
    </el-dialog>
  </main>
</template>
