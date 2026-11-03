<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, MagicStick, Refresh, WarningFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

import { analyzeJob, getJob, getJobAnalysis } from '@/api/job'
import AiWaiting from '@/components/AiWaiting.vue'
import AnalysisStateTag from '@/components/AnalysisStateTag.vue'
import StateBlock from '@/components/StateBlock.vue'

/**
 * 岗位 AI 分析（《前端原型设计》§5.6，接口《API接口设计》5.6–5.7）。
 *
 * 两类状态相互正交：
 * - `loading / loadError`：进入页面时的加载（GET 岗位详情 + GET 分析结果，并行）；
 * - `analyzing / analyzeError`：本次页内发起（POST analyze，超时 120 秒）。
 *
 * 与简历分析页的关键差异是**过期态（M5 独有）**：
 * - `stale` 由 GET analysis 返回——分析仍存在，但基于**旧版岗位描述**；
 * - 展示：顶部警告条（原描述更新时间 + 当前依据版本 + 重新分析入口）+ 旧结果**降对比度**
 *   并标注「基于旧版描述 · vN」（§5.6、原型 job-analysis.html 状态二）；
 * - 本次分析成功后 `stale` 必然为 false（结果基于刚才读到的描述），无需再查。
 *
 * 发起成功后的版本判断：每次重新分析都生成新版本（2026-10-13 起不做复用判断），
 * versionNo 必然递增，前端按响应中的版本号如实提示。
 */
const route = useRoute()
const router = useRouter()
const jobId = Number(route.params.id)

/* ---------------------------------------------------------------- 页面加载 */

const loading = ref(true)
const loadError = ref('')
const job = ref(null)
/** 最新一版成功分析；null 表示未分析 */
const analysis = ref(null)
/** 分析是否基于旧版描述（GET analysis 的 stale 字段） */
const stale = ref(false)

const analyzed = computed(() => Boolean(analysis.value))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [jobData, analysisData] = await Promise.all([
      getJob(jobId),
      getJobAnalysis(jobId),
    ])
    job.value = jobData
    analysis.value = analysisData.analyzed ? analysisData.analysis : null
    stale.value = analysisData.stale
  }
  catch (error) {
    loadError.value = error.message || '加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

/* ---------------------------------------------------------------- 发起分析 */

const analyzing = ref(false)
/** 本次发起的失败信息 { code, message }；null 表示无失败 */
const analyzeError = ref(null)

/** 发起前确认：说明耗时；过期态额外说明将按最新描述生成新版本 */
async function confirmAnalyze() {
  const text = stale.value
    ? '岗位描述在分析后已修改。重新分析会按最新描述生成新版本（调用一次大模型，通常需要 10–30 秒）。'
    : analyzed.value
      ? '重新分析会再次请求大模型并生成新版本（通常需要 10–30 秒）。'
      : '分析会调用一次大模型，通常需要 10–30 秒。'
  try {
    await ElMessageBox.confirm(
      text,
      analyzed.value ? '重新分析这个岗位？' : '发起岗位分析？',
      {
        confirmButtonText: analyzed.value ? '确定' : '开始分析',
        cancelButtonText: '取消',
      },
    )
    return true
  }
  catch {
    return false
  }
}

async function startAnalyze() {
  if (analyzing.value) {
    return
  }
  const confirmed = await confirmAnalyze()
  if (!confirmed) {
    return
  }
  analyzing.value = true
  analyzeError.value = null
  try {
    // 成功响应即最新分析结果（结构同 GET analysis 的 analysis），直接渲染、无需再查
    const result = await analyzeJob(jobId)
    analysis.value = result
    // 本次结果必然基于刚才读到的描述 → 不是过期结果
    stale.value = false
    // 每次重新分析都生成新版本（2026-10-13 起不再复用），版本号如实展示
    ElMessage.success(`分析完成（v${result.versionNo}）`)
  }
  catch (error) {
    analyzeError.value = { code: error.code, message: error.message }
  }
  finally {
    analyzing.value = false
  }
}

/** 502 系列文案（《前端原型设计》§5.6）；不写「不计费」——输出解析失败时模型已产生调用 */
const failText = computed(() => {
  const code = analyzeError.value?.code
  if (code === 50200) {
    return '大模型服务暂时不可用（50200）。本次分析没有产生任何记录，可以直接重试。'
  }
  if (code === 50201) {
    return '大模型输出无法解析（50201）。系统已丢弃本次结果，不会保存不完整的数据，可以重试。'
  }
  return analyzeError.value?.message || '分析失败，请稍后重试。'
})

/* ---------------------------------------------------------------- 岗位描述查看 */

const descVisible = ref(false)

/* ---------------------------------------------------------------- 展示工具 */

const formatTime = (iso) => (iso ? dayjs(iso).format('YYYY-MM-DD HH:mm') : '—')

onMounted(async () => {
  await load()
  // 工作台 / 优化链路入口（M20：列表页入口已删除）：加载完成后自动发起
  if (!loading.value && !loadError.value && route.query.auto === '1') {
    startAnalyze()
  }
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <el-button link :icon="ArrowLeft" class="page-head__back" @click="router.push('/jobs')">
          返回岗位列表
        </el-button>
        <h1>{{ job?.jobName || '岗位 AI 分析' }}</h1>
        <p v-if="job" class="page-head__desc">
          <template v-if="job.companyName">{{ job.companyName }} · </template>
          更新于 {{ formatTime(job.updatedAt) }}
        </p>
      </div>
      <AnalysisStateTag :analyzed="analyzed" :analyzing="analyzing" :stale="stale" />
    </div>

    <StateBlock :loading="loading" :error="loadError" @retry="load">
      <!-- 等待态 -->
      <AiWaiting
        v-if="analyzing"
        title="AI 正在阅读岗位要求…"
        hint="通常需要 10–30 秒。分析内容包括岗位职责、必备与加分技能、技术要求。"
      />

      <!-- 本次发起失败（保留页面上下文，仅展示错误与重试） -->
      <el-card v-else-if="analyzeError" shadow="never" class="card">
        <div class="fail">
          <el-icon :size="30" class="fail__icon"><WarningFilled /></el-icon>
          <h3 class="fail__title">分析未能完成</h3>
          <p class="fail__text">{{ failText }}</p>
          <div class="fail__actions">
            <el-button type="primary" @click="startAnalyze">重试分析</el-button>
            <el-button @click="router.push('/jobs')">返回列表</el-button>
          </div>
          <p class="fail__note">如果多次重试仍失败，可能是模型服务限流或网络异常，请稍后再试。</p>
        </div>
      </el-card>

      <!-- 未分析 -->
      <el-card v-else-if="!analyzed" shadow="never" class="card">
        <div class="empty">
          <el-icon :size="30" class="empty__icon"><MagicStick /></el-icon>
          <h3 class="empty__title">这个岗位还没有分析过</h3>
          <p class="empty__text">
            分析会从岗位描述中提取职责、必备技能与加分项，是生成优化报告的必要前提。
            通常需要 10–30 秒。
          </p>
          <el-button type="primary" @click="startAnalyze">发起岗位分析</el-button>
        </div>
      </el-card>

      <!-- 已分析：结果（过期时加警告条并降对比度） -->
      <div v-else>
        <el-alert
          v-if="stale"
          class="stale-alert"
          type="warning"
          :closable="false"
          show-icon
        >
          <template #title>
            当前分析基于旧版岗位描述
          </template>
          <p class="stale-alert__text">
            岗位描述已于 {{ formatTime(job?.updatedAt) }} 更新，下方结果来自更新前的描述（v{{ analysis.versionNo }}）。
            重新分析后才会按最新描述生成匹配依据。
          </p>
          <div class="stale-alert__actions">
            <el-button size="small" type="primary" :icon="Refresh" @click="startAnalyze">
              重新分析岗位
            </el-button>
            <el-button size="small" @click="descVisible = true">
              查看岗位描述
            </el-button>
          </div>
        </el-alert>

        <div class="layout" :class="{ 'layout--stale': stale }">
          <div class="layout__main">
            <el-card shadow="never" class="card">
              <template #header>
                <div class="card__head">
                  <h2>岗位概述</h2>
                  <el-tag v-if="stale" type="warning" size="small" effect="light">
                    基于旧版描述 · v{{ analysis.versionNo }}
                  </el-tag>
                  <el-tag v-else type="success" size="small" effect="light">分析成功</el-tag>
                </div>
              </template>
              <p class="summary">{{ analysis.summary || '（模型未给出概述）' }}</p>
            </el-card>

            <el-card shadow="never" class="card">
              <template #header>
                <div class="card__head">
                  <h2>岗位职责</h2>
                  <span class="card__sub">共 {{ analysis.responsibilities?.length || 0 }} 条</span>
                </div>
              </template>
              <ul v-if="analysis.responsibilities?.length" class="bullets">
                <li v-for="(item, index) in analysis.responsibilities" :key="index">{{ item }}</li>
              </ul>
              <p v-else class="muted">
                模型未从描述中提取到岗位职责。
              </p>
            </el-card>

            <div class="grid-2">
              <el-card shadow="never" class="card">
                <template #header>
                  <div class="card__head">
                    <h2>必备技能</h2>
                    <span class="card__sub">共 {{ analysis.requiredSkills?.length || 0 }} 项</span>
                  </div>
                </template>
                <div v-if="analysis.requiredSkills?.length" class="tag-row">
                  <el-tag
                    v-for="(item, index) in analysis.requiredSkills"
                    :key="index"
                    size="large"
                    effect="light"
                  >
                    {{ item }}
                  </el-tag>
                </div>
                <p v-else class="muted">未识别到明确的必备技能。</p>
              </el-card>

              <el-card shadow="never" class="card">
                <template #header>
                  <div class="card__head">
                    <h2>加分技能</h2>
                    <span class="card__sub">共 {{ analysis.preferredSkills?.length || 0 }} 项</span>
                  </div>
                </template>
                <div v-if="analysis.preferredSkills?.length" class="tag-row">
                  <el-tag
                    v-for="(item, index) in analysis.preferredSkills"
                    :key="index"
                    type="info"
                    size="large"
                    effect="light"
                  >
                    {{ item }}
                  </el-tag>
                </div>
                <p v-else class="muted">未识别到加分技能。</p>
              </el-card>
            </div>

            <el-card shadow="never" class="card">
              <template #header>
                <h2>技术与要求明细</h2>
              </template>
              <div class="detail-row">
                <div class="detail-row__label">技术栈</div>
                <div class="detail-row__value">
                  <template v-if="analysis.technologyStack?.length">
                    <el-tag
                      v-for="(item, index) in analysis.technologyStack"
                      :key="index"
                      size="small"
                      effect="light"
                      class="tag-inline"
                    >
                      {{ item }}
                    </el-tag>
                  </template>
                  <span v-else class="muted">未识别到技术栈。</span>
                </div>
              </div>
              <div class="detail-row">
                <div class="detail-row__label">学历要求</div>
                <div class="detail-row__value">
                  {{ analysis.educationRequirement || '未提及' }}
                </div>
              </div>
              <div class="detail-row">
                <div class="detail-row__label">经验要求</div>
                <div class="detail-row__value">
                  {{ analysis.experienceRequirement || '未提及' }}
                </div>
              </div>
              <div class="detail-row">
                <div class="detail-row__label">软性要求</div>
                <div class="detail-row__value">
                  <template v-if="analysis.softSkills?.length">
                    {{ analysis.softSkills.join('、') }}
                  </template>
                  <span v-else class="muted">未提及</span>
                </div>
              </div>
            </el-card>
          </div>

          <div class="layout__side">
            <el-card shadow="never" class="card">
              <template #header>
                <h2>岗位信息</h2>
              </template>
              <dl class="desc">
                <div class="desc__row">
                  <dt>公司</dt>
                  <dd>{{ job?.companyName || '—' }}</dd>
                </div>
                <div class="desc__row">
                  <dt>岗位</dt>
                  <dd>{{ job?.jobName }}</dd>
                </div>
                <div class="desc__row">
                  <dt>分析版本</dt>
                  <dd>v{{ analysis.versionNo }}</dd>
                </div>
                <div class="desc__row">
                  <dt>分析时间</dt>
                  <dd>{{ formatTime(analysis.createdAt) }}</dd>
                </div>
                <div class="desc__row">
                  <dt>使用模型</dt>
                  <dd>{{ analysis.modelName }}</dd>
                </div>
              </dl>
              <p class="side-note">
                每次重新分析都会调用大模型生成新版本，旧版本仍保留可追溯。
              </p>
              <el-button class="side-action" :icon="Refresh" @click="startAnalyze">
                重新分析
              </el-button>
            </el-card>
          </div>
        </div>
      </div>
    </StateBlock>

    <!-- 当前岗位描述（过期警告条的「查看岗位描述」入口） -->
    <el-dialog v-model="descVisible" title="当前岗位描述" width="640px">
      <p class="desc-view__meta">
        <template v-if="job?.companyName">{{ job.companyName }} · </template>{{ job?.jobName }}
      </p>
      <p class="desc-view__text">{{ job?.description }}</p>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head__back {
  margin-bottom: 6px;
  font-size: 13px;
}

.page-head h1 {
  font-size: 22px;
  margin-bottom: 4px;
}

.page-head__desc {
  font-size: 13px;
  color: var(--ink-500);
}

/* ---- 过期警告条 ---- */
.stale-alert {
  margin-bottom: 16px;
}

.stale-alert__text {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.8;
}

.stale-alert__actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* 旧结果整体降对比度（原型 §5.6 的 opacity:.72） */
.layout--stale {
  opacity: 0.72;
}

/* ---- 卡片通用 ---- */
.card {
  border-radius: var(--radius);
}

.card + .card,
.layout__main > * + * {
  margin-top: 16px;
}

.card :deep(.el-card__header) {
  padding: 14px 18px;
}

.card :deep(.el-card__body) {
  padding: 18px;
}

.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card__head h2,
.card h2 {
  font-size: 17px;
}

.card__sub {
  font-size: 13px;
  color: var(--ink-500);
}

/* ---- 结果布局 ---- */
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1080px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.summary {
  font-size: 14px;
  line-height: 1.9;
  color: var(--ink-700);
}

.muted {
  font-size: 13px;
  color: var(--ink-400);
}

/* 职责列表 */
.bullets {
  padding-left: 18px;
  font-size: 13.5px;
  line-height: 1.9;
  color: var(--ink-700);
  list-style: disc;
}

.bullets li + li {
  margin-top: 2px;
}

/* 技术标签行（必备 / 加分技能） */
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-inline {
  margin: 0 6px 6px 0;
}

/* 技术与要求明细 */
.detail-row {
  display: flex;
  gap: 14px;
  padding: 9px 0;
  font-size: 13.5px;
}

.detail-row + .detail-row {
  border-top: 1px dashed var(--line);
}

.detail-row__label {
  flex: 0 0 76px;
  color: var(--ink-500);
}

.detail-row__value {
  flex: 1;
  min-width: 0;
  color: var(--ink-900);
  line-height: 1.8;
  word-break: break-word;
}

/* ---- 右侧栏 ---- */
.desc {
  font-size: 13px;
}

.desc__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
}

.desc__row + .desc__row {
  border-top: 1px dashed var(--line);
}

.desc__row dt {
  color: var(--ink-500);
  flex: 0 0 auto;
}

.desc__row dd {
  color: var(--ink-900);
  text-align: right;
  word-break: break-all;
}

.side-note {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
}

.side-action {
  margin-top: 12px;
  width: 100%;
}

/* ---- 未分析 / 失败 ---- */
.empty,
.fail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 20px;
  text-align: center;
}

.empty__icon {
  color: var(--brand-600);
}

.empty__title,
.fail__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-900);
}

.empty__text,
.fail__text {
  max-width: 460px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-500);
}

.fail__icon {
  color: var(--danger, #c45656);
}

.fail__actions {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}

.fail__note {
  font-size: 12px;
  color: var(--ink-400);
}

/* ---- 岗位描述查看 ---- */
.desc-view__meta {
  font-size: 13px;
  color: var(--ink-500);
  margin-bottom: 10px;
}

.desc-view__text {
  max-height: 50vh;
  overflow-y: auto;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--bg);
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
