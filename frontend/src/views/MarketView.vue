<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api'

const activeTab = ref('catalog')
const listings = ref([])
const pushes = ref([])
const demands = ref([])
const stats = ref({})
const loading = ref(false)

const filter = reactive({ keyword: '', domain: '', maxPriceCents: 0 })
const detailVisible = ref(false)
const detail = ref(null)

const demandVisible = ref(false)
const demandForm = reactive({ title: '', rawDesc: '', budgetCents: 0 })

const STATE = { 0: { text: '新推送', type: 'warning' }, 1: { text: '已读', type: 'info' }, 2: { text: '已申请交付', type: 'success' }, 3: { text: '已失效', type: 'info' } }

const domains = computed(() => [...new Set(listings.value.map((l) => l.domain))])

function parseJson(text, fallback) {
  try { return JSON.parse(text) } catch { return fallback }
}

function stateOf(item) {
  return STATE[item.state] || STATE[0]
}

function money(cents) {
  return cents > 0 ? '¥' + (cents / 100).toFixed(2) : '免费'
}

async function load() {
  loading.value = true
  try {
    const [list, pushList, demandList, stat] = await Promise.all([
      api.marketListings(filter), api.marketPushes(), api.marketDemands(), api.marketStats(),
    ])
    listings.value = list
    pushes.value = pushList
    demands.value = demandList
    stats.value = stat
  } finally {
    loading.value = false
  }
}

async function openDetail(listing) {
  detail.value = await api.marketListing(listing.id)
  detailVisible.value = true
}

async function submitDemand() {
  if (demandForm.title.trim().length < 2 || demandForm.rawDesc.trim().length < 10) {
    ElMessage.warning('请填写标题，并把需求描述写清楚（至少 10 个字）')
    return
  }
  await api.marketCreateDemand({ ...demandForm })
  ElMessage.success('需求已发布，市场已匹配并推送货源')
  demandVisible.value = false
  demandForm.title = ''
  demandForm.rawDesc = ''
  demandForm.budgetCents = 0
  activeTab.value = 'pushes'
  await load()
}

async function requestDelivery(item) {
  await api.marketRequestDelivery(item.id, '希望获取该产品的聚合数据')
  ElMessage.success('已向卖方申请交付')
  await load()
}

async function markRead(item) {
  await api.marketReadPush(item.id)
  await load()
}

async function rematch() {
  for (const demand of demands.value) {
    await api.marketMatch(demand.id)
  }
  ElMessage.success('已重新匹配')
  await load()
}

onMounted(load)
</script>

<template>
  <main class="page">
    <div class="page-head">
      <div>
        <h2>数据市场</h2>
        <p class="page-sub">
          这里只展示卖方 Agent 加工出的<strong>数据产品描述</strong>（字段、覆盖量、质量、脱敏样例），看不到任何原始文件。
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" @click="demandVisible = true">发布采购需求</el-button>
      </div>
    </div>

    <div class="market-stats">
      <div class="stat-chip"><span class="num">{{ stats.activeListings || 0 }}</span><span>在架货源</span></div>
      <div class="stat-chip"><span class="num">{{ stats.unreadPushes || 0 }}</span><span>未读推送</span></div>
      <div class="stat-chip"><span class="num">{{ stats.products || 0 }}</span><span>数据产品</span></div>
      <div class="stat-chip"><span class="num">{{ stats.collabs || 0 }}</span><span>协同会话</span></div>
    </div>

    <el-tabs v-model="activeTab" v-loading="loading" class="market-tabs">
      <el-tab-pane label="货源目录" name="catalog">
        <div class="filter-row">
          <el-input v-model="filter.keyword" placeholder="搜索标题/标签/字段" clearable style="width: 260px" @keyup.enter="load" @clear="load">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="filter.domain" placeholder="全部品类" clearable style="width: 160px" @change="load">
            <el-option v-for="d in domains" :key="d" :label="d" :value="d" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="load">搜索</el-button>
        </div>

        <el-table :data="listings" class="file-table">
          <el-table-column label="货源" min-width="240">
            <template #default="{ row }">
              <div class="listing-title">{{ row.title }}</div>
              <div class="listing-summary">{{ row.summary }}</div>
            </template>
          </el-table-column>
          <el-table-column label="品类" width="110">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.domain }}</el-tag></template>
          </el-table-column>
          <el-table-column label="字段" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="f in parseJson(row.fieldList, [])" :key="f" size="small" effect="plain" class="field-tag">{{ f }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="覆盖" width="170">
            <template #default="{ row }">
              <div class="small-text">{{ row.coverageStart || '—' }} ~ {{ row.coverageEnd || '—' }}</div>
              <div class="small-text">{{ row.rowCount }} 原始行 · 质量 {{ row.qualityScore }}</div>
            </template>
          </el-table-column>
          <el-table-column label="卖方" width="110" prop="sellerName" />
          <el-table-column label="价格" width="90">
            <template #default="{ row }">{{ money(row.priceCents) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="right">
            <template #default="{ row }">
              <el-button text size="small" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !listings.length" description="还没有在架货源，去卖方工作台加工一个" />
      </el-tab-pane>

      <el-tab-pane label="我的推送" name="pushes">
        <div class="filter-row">
          <el-button :icon="Refresh" size="small" @click="rematch">重新匹配我的需求</el-button>
        </div>
        <el-table :data="pushes" class="file-table">
          <el-table-column label="货源" min-width="240">
            <template #default="{ row }">
              <div class="listing-title">{{ row.listingTitle }}</div>
              <div class="listing-summary">{{ row.listingSummary }}</div>
            </template>
          </el-table-column>
          <el-table-column label="匹配度" width="90">
            <template #default="{ row }"><span class="score">{{ row.score }}</span></template>
          </el-table-column>
          <el-table-column label="命中理由" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="r in parseJson(row.reasonsJson, [])" :key="r" size="small" effect="plain" class="field-tag">{{ r }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="卖方" width="100" prop="sellerName" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><el-tag size="small" :type="stateOf(row).type" effect="plain">{{ stateOf(row).text }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button v-if="row.state === 0" text size="small" @click="markRead(row)">标为已读</el-button>
              <el-button v-if="row.state < 2" text size="small" type="primary" @click="requestDelivery(row)">申请交付</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !pushes.length" description="还没有推送，先发布一条采购需求" />
      </el-tab-pane>

      <el-tab-pane label="我的需求" name="demands">
        <el-table :data="demands" class="file-table">
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="rawDesc" label="描述" min-width="280" show-overflow-tooltip />
          <el-table-column label="预算" width="100">
            <template #default="{ row }">{{ money(row.budgetCents) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="170" />
          <el-table-column label="操作" width="120" align="right">
            <template #default="{ row }">
              <el-button text size="small" @click="api.marketMatch(row.id).then(load)">重新匹配</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !demands.length" description="还没有发布需求" />
      </el-tab-pane>
    </el-tabs>

    <!-- 货源详情 -->
    <el-drawer v-model="detailVisible" :title="detail?.listing?.title || '货源详情'" size="52%">
      <div v-if="detail" class="listing-detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="品类">{{ detail.listing.domain }}</el-descriptions-item>
          <el-descriptions-item label="卖方">{{ detail.listing.sellerName }}</el-descriptions-item>
          <el-descriptions-item label="覆盖区间">{{ detail.listing.coverageStart || '—' }} ~ {{ detail.listing.coverageEnd || '—' }}</el-descriptions-item>
          <el-descriptions-item label="原始行">{{ detail.listing.rowCount }}</el-descriptions-item>
          <el-descriptions-item label="质量分">{{ detail.listing.qualityScore }}</el-descriptions-item>
          <el-descriptions-item label="许可">{{ detail.listing.licenseType === 'open' ? '可再分发' : '仅内部使用' }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">产品字段</h4>
        <div>
          <el-tag v-for="f in parseJson(detail.listing.fieldList, [])" :key="f" size="small" effect="plain" class="field-tag">{{ f }}</el-tag>
        </div>

        <h4 class="section-title">质量指标</h4>
        <pre class="preview-text">{{ detail.qualityJson }}</pre>

        <h4 class="section-title">脱敏样例（买方可见）</h4>
        <pre class="preview-text">{{ detail.sampleJson }}</pre>

        <h4 class="section-title">构建凭证</h4>
        <pre class="preview-text">{{ detail.buildReceipt }}</pre>
      </div>
    </el-drawer>

    <!-- 发布需求 -->
    <el-dialog v-model="demandVisible" title="发布采购需求" width="620px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="demandForm.title" placeholder="例如：需要本地通勤时效数据" /></el-form-item>
        <el-form-item label="描述">
          <el-input v-model="demandForm.rawDesc" type="textarea" :rows="6" placeholder="写清要什么内容、什么范围、不要什么" />
        </el-form-item>
        <el-form-item label="预算(分)"><el-input-number v-model="demandForm.budgetCents" :min="0" :step="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="demandVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDemand">发布并匹配</el-button>
      </template>
    </el-dialog>
  </main>
</template>
