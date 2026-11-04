<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'
import { auth } from '../store/auth'

const router = useRouter()
const bounties = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ title: '', rawDesc: '' })

const sampleDesc = [
  '需求：自己整理的家常菜做法素材。',
  '要的内容：菜谱文档、做法记录表格、成品照片。',
  '文件类型：Word、表格、图片。',
  '不要：网上复制的网红菜谱、外卖菜单截图。',
].join('\n')

const FORMAT_LABELS = {
  image: '图片',
  docx: 'Word',
  xlsx: '表格',
  text: '纯文本',
  pdf: 'PDF',
}

async function load() {
  loading.value = true
  try {
    bounties.value = await api.bountyList()
  } finally {
    loading.value = false
  }
}

function structured(bounty) {
  try {
    return JSON.parse(bounty.structuredJson)
  } catch {
    return {}
  }
}

function formatsOf(bounty) {
  return (structured(bounty).formats || []).map((item) => FORMAT_LABELS[item] || item)
}

function goScan(bounty) {
  router.push({ name: 'agent', query: { bountyId: bounty.id } })
}

function openPublish() {
  form.title = ''
  form.rawDesc = sampleDesc
  dialogVisible.value = true
}

async function submit() {
  if (!form.title.trim() || form.rawDesc.trim().length < 10) {
    ElMessage.warning('请填写标题，并把悬赏描述写清楚（至少 10 个字）')
    return
  }
  submitting.value = true
  try {
    await api.bountyCreate({ title: form.title.trim(), rawDesc: form.rawDesc.trim() })
    ElMessage.success('悬赏已发布，系统已自动解析成结构化需求')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(bounty) {
  const next = bounty.status === 1 ? 2 : 1
  try {
    await ElMessageBox.confirm(
      next === 2 ? '停用后个人用户无法再针对这条悬赏发起扫描，确定停用？' : '重新启用这条悬赏？',
      next === 2 ? '停用悬赏' : '启用悬赏',
      { type: 'warning' },
    )
    await api.bountyStatus(bounty.id, next)
    ElMessage.success('已更新')
    await load()
  } catch {
    /* 取消 */
  }
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>悬赏广场</h2>
        <p class="page-sub">企业发布数据要素需求，个人用户在自己的网盘里授权扫描，由 Agent 解析真实内容后给出候选推荐。</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="auth.isEnterprise" type="primary" :icon="Plus" @click="openPublish">发布悬赏</el-button>
      </div>
    </div>

    <div v-loading="loading" class="bounty-grid">
      <el-card v-for="bounty in bounties" :key="bounty.id" shadow="never" class="bounty-card">
        <div class="bounty-head">
          <span class="bounty-title">{{ bounty.title }}</span>
          <el-tag size="small" :type="bounty.status === 1 ? 'success' : 'info'" effect="plain">
            {{ bounty.statusLabel }}
          </el-tag>
        </div>
        <div class="bounty-meta">
          <span>发布方：{{ bounty.publisherName }}</span>
          <span>{{ bounty.createdAt }}</span>
        </div>

        <div class="chip-row">
          <el-tag size="small" effect="dark" type="primary">{{ structured(bounty).domainLabel || '通用资料' }}</el-tag>
          <el-tag v-for="item in formatsOf(bounty)" :key="item" size="small" effect="plain">{{ item }}</el-tag>
          <el-tag size="small" :type="bounty.structuredBy === 'llm' ? 'success' : 'info'" effect="plain">
            {{ bounty.structuredBy === 'llm' ? 'DeepSeek 解析' : '规则解析' }}
          </el-tag>
        </div>

        <p class="bounty-desc">{{ bounty.rawDesc }}</p>

        <div class="bounty-foot">
          <span class="bounty-stat">已被扫描 {{ bounty.scanTaskCount }} 次 · 高匹配 {{ bounty.highMatchCount }} 条</span>
          <div class="bounty-actions">
            <el-button size="small" type="primary" plain @click="goScan(bounty)">发起扫描</el-button>
            <el-button
              v-if="bounty.mine || auth.isAdmin"
              size="small"
              text
              @click="toggleStatus(bounty)"
            >{{ bounty.status === 1 ? '停用' : '启用' }}</el-button>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && !bounties.length" description="还没有悬赏，企业用户可以点右上角发布一条" />
    </div>

    <el-dialog v-model="dialogVisible" title="发布数据要素悬赏" width="640px">
      <el-form label-position="top">
        <el-form-item label="悬赏标题">
          <el-input v-model="form.title" placeholder="例如：悬赏 R-06｜家常菜做法素材" />
        </el-form-item>
        <el-form-item label="悬赏描述（自然语言，写清要什么、什么格式、不要什么）">
          <el-input v-model="form.rawDesc" type="textarea" :rows="8" />
        </el-form-item>
      </el-form>
      <p class="dialog-hint">发布后系统会自动把描述解析成结构化需求（领域、可用格式、需排除的内容），个人用户据此授权扫描。</p>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">发布并解析</el-button>
      </template>
    </el-dialog>
  </main>
</template>
