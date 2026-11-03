<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Download, MagicStick, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

import { getJob } from '@/api/job'
import {
  evaluateOptimization,
  exportOptimization,
  getOptimization,
  retryOptimization,
  saveOptimizationContent,
} from '@/api/optimization'
import { getResume } from '@/api/resume'
import AiWaiting from '@/components/AiWaiting.vue'
import ChangeCompare from '@/components/ChangeCompare.vue'
import DimensionBars from '@/components/DimensionBars.vue'
import ScoreRing from '@/components/ScoreRing.vue'
import StateBlock from '@/components/StateBlock.vue'
import { diffLines } from '@/utils/textDiff'

/**
 * 优化工作台（《前端原型设计》§5.8，接口《API接口设计》§6A.3–6A.6）。
 *
 * 区块：① 优化前后匹配度 ② 维度对比 ③ 岗位要求 ④ 简历优势 ⑤ 对应关系
 * ⑥ 差距 + 待确认问题 ⑦ 逐项修改说明 ⑧ 简历对比与编辑；右栏为优化依据与操作。
 *
 * 三层内容严格分列（不得混淆）：原始简历只读、模型原稿 `optimizedContent` 不可变、
 * 编辑器读写的是 `editedContent`（保存、评估与导出都以它为优先）。
 *
 * 措辞硬性要求沿用《前端原型设计》§5.8：只写「简历未体现」「缺少相关证据」；
 * 禁止「不具备」类断言；分数不得解释为录用概率；**不得宣称优化必然提升分数**
 * （改写不改变结构化事实，前后分数持平或下降均属正常）。
 */
const route = useRoute()
const router = useRouter()
const optimizationId = Number(route.params.id)

const loading = ref(true)
const loadError = ref('')
const report = ref(null)

/** 关联资源是否已被逻辑删除（详情接口 404 即已删除） */
const resumeMissing = ref(false)
const jobMissing = ref(false)

/* ---------------------------------------------------------------- 编辑器 */

/** 编辑草稿（初始为 editedContent，为空时取模型原稿） */
const draft = ref('')
const saving = ref(false)
const evaluating = ref(false)
const exporting = ref(false)

/** 当前生效内容（与服务端同口径：用户稿优先，无编辑稿时回落模型原稿） */
const currentContent = computed(
  () => report.value?.editedContent ?? report.value?.optimizedContent ?? '',
)

/** 有未保存改动（草稿与当前生效内容不同） */
const dirty = computed(() => report.value !== null && draft.value !== currentContent.value)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    report.value = await getOptimization(optimizationId)
    draft.value = report.value.editedContent ?? report.value.optimizedContent ?? ''
    probeSources()
  }
  catch (error) {
    loadError.value = error.message || '加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
  // M16：任务进行中（PENDING/PROCESSING）时启动轮询；完成或失败即停止
  startPollingIfNeeded()
}

/** 探测源资源是否仍可访问（404 即已删除）；探测失败不影响报告本体 */
async function probeSources() {
  const [resumeResult, jobResult] = await Promise.allSettled([
    getResume(report.value.resumeId),
    getJob(report.value.jobId),
  ])
  resumeMissing.value = resumeResult.status === 'rejected' && resumeResult.reason?.code === 40400
  jobMissing.value = jobResult.status === 'rejected' && jobResult.reason?.code === 40400
}

onMounted(load)

/* ---------- M16：任务态（异步化后 PENDING/PROCESSING/FAILED 的轮询与重试） ---------- */

/** 任务未成功完成时返回其状态；SUCCESS 或无记录时为 null */
const taskState = computed(() => {
  const status = report.value?.status
  if (!status || status === 'SUCCESS') {
    return null
  }
  return status
})

/** 阶段文案（PENDING/PROCESSING 的进度展示） */
const stageText = computed(() => {
  const stage = report.value?.stage
  if (stage === 'MATCHING') {
    return '正在进行岗位匹配分析…'
  }
  if (stage === 'GENERATING') {
    return '正在生成优化稿…'
  }
  return '任务已受理，正在排队等待后台执行…'
})

let pollTimer = null

/** 任务进行中时每 3 秒轮询详情；完成或失败即停止 */
function startPollingIfNeeded() {
  stopPolling()
  if (!taskState.value || taskState.value === 'FAILED') {
    return
  }
  pollTimer = setInterval(async () => {
    try {
      // 先记住更新前的"未保存改动"状态：report 一旦替换，dirty 会立刻变为 true（草稿还是空串），
      // 必须在替换前判断，否则用户未编辑时也会跳过下方同步
      const wasDirty = dirty.value
      report.value = await getOptimization(optimizationId)
      // 用户尚未编辑时，把完成后的当前稿同步进编辑器（与 load 的初始化口径一致）
      if (!wasDirty) {
        draft.value = report.value.editedContent ?? report.value.optimizedContent ?? ''
      }
      if (!taskState.value || taskState.value === 'FAILED') {
        stopPolling()
        if (taskState.value !== 'FAILED') {
          ElMessage.success('优化已完成')
        }
      }
    }
    catch {
      // 单次轮询失败忽略（下一轮再试），不打断页面
    }
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(stopPolling)

const retrying = ref(false)

/** 手动重试失败任务（6A.7，M16） */
async function handleRetry() {
  retrying.value = true
  try {
    report.value = await retryOptimization(optimizationId)
    ElMessage.success('已重新提交，任务转入后台执行')
    startPollingIfNeeded()
  }
  catch (error) {
    ElMessage.error(error.message)
  }
  finally {
    retrying.value = false
  }
}

/** 未保存改动时拦截浏览器关闭/刷新（页面内操作另有提示） */
function onBeforeUnload(event) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', onBeforeUnload))

/* ---------------------------------------------------------------- 展示辅助 */

const formatTime = (iso) => (iso ? dayjs(iso).format('YYYY-MM-DD HH:mm') : '—')

const displayScore = (score) => (score === null || score === undefined ? '—' : Number(score))

/** dimension key → 中文标签：取自接口 items（前端不得硬编码维度名） */
const dimensionLabels = computed(() => {
  const map = {}
  for (const item of report.value?.dimensionScores?.items ?? []) {
    map[item.key] = item.label
  }
  return map
})

const dimensionLabel = (key) => dimensionLabels.value[key] ?? key

const matchedItems = computed(() => report.value?.matchedItems ?? [])
const partialItems = computed(() => report.value?.partialItems ?? [])
const missingItems = computed(() => report.value?.missingItems ?? [])
const gapQuestions = computed(() => report.value?.gapQuestions ?? [])
const changes = computed(() => report.value?.changes ?? [])
const advantages = computed(() => report.value?.resumeAdvantages ?? [])
const requirements = computed(() => report.value?.jobRequirements ?? null)
const editHistory = computed(() => report.value?.editHistory ?? [])

const evaluated = computed(() => report.value?.scoreAfter !== null && report.value?.scoreAfter !== undefined)

/** 评级标签（仅作视觉分级，不改变分数的含义） */
function scoreTag(score) {
  if (score === null || score === undefined) {
    return { type: 'info', text: '待评估' }
  }
  if (score >= 80) {
    return { type: 'success', text: '匹配良好' }
  }
  if (score >= 60) {
    return { type: 'primary', text: '匹配中等' }
  }
  return { type: 'warning', text: '匹配偏低' }
}

/* ---------------------------------------------------------------- 操作 */

/** 保存编辑稿（不覆盖模型原稿；服务端负责历史版本） */
async function save() {
  if (!dirty.value || saving.value) {
    return
  }
  saving.value = true
  try {
    report.value = await saveOptimizationContent(optimizationId, draft.value)
    draft.value = report.value.editedContent ?? draft.value
    ElMessage.success('已保存')
  }
  catch (error) {
    ElMessage.error(error.message || '保存失败，请稍后重试')
  }
  finally {
    saving.value = false
  }
}

/** 重新评估（取已保存的当前稿；有未保存改动时先提示） */
async function evaluate() {
  if (evaluating.value) {
    return
  }
  if (dirty.value) {
    try {
      await ElMessageBox.confirm(
        '编辑器里有未保存的修改。保存后再评估？',
        '还有未保存的修改',
        { confirmButtonText: '先保存再评估', cancelButtonText: '取消', type: 'warning' },
      )
    }
    catch {
      return
    }
    await save()
    if (dirty.value) {
      return
    }
  }
  evaluating.value = true
  try {
    report.value = await evaluateOptimization(optimizationId)
    draft.value = report.value.editedContent ?? report.value.optimizedContent ?? ''
    ElMessage.success('评估完成')
  }
  catch (error) {
    ElMessage.error(`${error.message || '评估失败'}（原有评估结果不受影响，可重试）`)
  }
  finally {
    evaluating.value = false
  }
}

/** 导出 DOCX：内容取已保存的当前稿（编辑稿优先） */
async function exportDocx() {
  if (exporting.value) {
    return
  }
  if (dirty.value) {
    ElMessage.warning('请先保存当前修改，导出的是已保存的内容')
    return
  }
  exporting.value = true
  try {
    const blob = await exportOptimization(optimizationId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    // 响应拦截器只返回 data（Blob），拿不到响应头文件名——按与后端一致的规则自行拼装
    link.download = safeFileName(`${report.value.resumeName}-${report.value.jobName}-优化稿`) + '.docx'
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  }
  catch (error) {
    ElMessage.error(error.message || '导出失败，请稍后重试')
  }
  finally {
    exporting.value = false
  }
}

/** 与后端 safeFileName 同规则：剔除路径与非法字符、截断长度 */
function safeFileName(name) {
  const cleaned = name.replace(/[\\/:*?"<>|\r\n]/g, '_').trim()
  return (cleaned || '简历-优化稿').slice(0, 120)
}

/** 历史版本：回填到编辑器（不直接保存） */
function fillHistory(snapshot) {
  draft.value = snapshot.content
  ElMessage.info('已回填到编辑器，确认后点「保存编辑」才会生效')
}

/* ---------------------------------------------------------------- 版本对比（M13） */

const compareVisible = ref(false)
const compareSnapshot = ref(null)

/** 行内差异：所选历史版本 → 当前稿（纯前端 LCS，不调后端；editHistory 已含全文） */
const compareDiff = computed(() => {
  if (!compareSnapshot.value) {
    return []
  }
  return diffLines(compareSnapshot.value.content ?? '', currentContent.value)
})

function openCompare(snapshot) {
  compareSnapshot.value = snapshot
  compareVisible.value = true
}

/** 对比对话框内回填：复用 fillHistory 的语义（进编辑器、需显式保存），随后关闭对话框 */
function fillHistoryFromCompare() {
  if (compareSnapshot.value) {
    fillHistory(compareSnapshot.value)
  }
  compareVisible.value = false
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <el-button link :icon="ArrowLeft" class="page-head__back" @click="router.push('/optimizations')">
          返回优化列表
        </el-button>
        <h1>优化工作台</h1>
        <p v-if="report" class="page-head__desc">
          {{ report.jobName }}<template v-if="report.companyName"> · {{ report.companyName }}</template>
          · 使用「{{ report.resumeName }}」 · v{{ report.versionNo }} · {{ formatTime(report.createdAt) }}
        </p>
      </div>
    </div>

    <StateBlock :loading="loading" :error="loadError" @retry="load">
      <!-- M16：任务进行中 / 失败态（异步化后的 PENDING/PROCESSING/FAILED） -->
      <el-card v-if="report && taskState" shadow="never" class="card task-card">
        <template #header>
          <div class="card__head">
            <h2>优化任务</h2>
            <el-tag :type="taskState === 'FAILED' ? 'danger' : 'primary'" size="small" effect="light">
              {{ taskState === 'FAILED' ? '生成失败' : '生成中' }}
            </el-tag>
          </div>
        </template>
        <template v-if="taskState === 'FAILED'">
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="report.errorMessage || '任务执行失败，可重试'"
          />
          <!-- V1.13：errorMessage 只含前 5 项汇总，这里展开全部校验问题（已脱敏） -->
          <el-collapse v-if="report.failureIssues?.length" class="task-card__issues">
            <el-collapse-item
              :title="`校验问题明细（共 ${report.failureIssues.length} 项）`"
              name="issues"
            >
              <ul class="task-card__issue-list">
                <li v-for="(issue, index) in report.failureIssues" :key="index">
                  <el-tag
                    size="small"
                    effect="plain"
                    :type="issue.severity === 'RISK' ? 'warning' : 'danger'"
                  >
                    {{ issue.severity === 'RISK' ? '风险' : '阻断' }}
                  </el-tag>
                  <span>{{ issue.message }}</span>
                </li>
              </ul>
            </el-collapse-item>
          </el-collapse>
          <div class="task-card__foot">
            <el-button type="primary" :loading="retrying" @click="handleRetry">
              重试
            </el-button>
            <el-button @click="router.push({ name: 'optimizations' })">
              返回列表
            </el-button>
          </div>
        </template>
        <template v-else>
          <AiWaiting
            :title="stageText"
            hint="任务在后台执行（通常 1–3 分钟），可以离开此页面，稍后回来查看。"
            leave-hint="进度每 3 秒自动刷新。"
          />
        </template>
      </el-card>

      <div v-else-if="report" class="layout">
        <div class="layout__main">
          <!-- ① 优化前后匹配度 + ② 维度对比 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>优化前后匹配度</h2>
                <span class="card__sub">
                  两组分数均由系统按固定权重计算 · 评分规则 {{ report.dimensionScores?.ruleVersion }}
                </span>
              </div>
            </template>

            <div class="score-row">
              <div class="score-row__side">
                <ScoreRing :score="report.matchScore" />
                <el-tag class="score-row__tag" :type="scoreTag(report.matchScore).type" size="small" effect="light">
                  {{ scoreTag(report.matchScore).text }}
                </el-tag>
                <p class="score-row__label">优化前</p>
              </div>

              <div class="score-row__arrow">→</div>

              <div class="score-row__side">
                <template v-if="evaluated">
                  <ScoreRing :score="report.scoreAfter" />
                  <el-tag class="score-row__tag" :type="scoreTag(report.scoreAfter).type" size="small" effect="light">
                    {{ scoreTag(report.scoreAfter).text }}
                  </el-tag>
                </template>
                <template v-else>
                  <div class="score-row__pending">
                    <span class="score-row__pending-text">待评估</span>
                  </div>
                  <el-button
                    type="primary"
                    size="small"
                    :icon="Refresh"
                    :loading="evaluating"
                    @click="evaluate"
                  >
                    重新评估
                  </el-button>
                </template>
                <p class="score-row__label">
                  优化后<template v-if="evaluated">（{{ formatTime(report.evaluatedAt) }}）</template>
                </p>
              </div>
            </div>

            <div class="dims">
              <div class="dims__col">
                <h3 class="dims__title">优化前维度得分</h3>
                <DimensionBars :items="report.dimensionScores?.items ?? []" />
              </div>
              <div class="dims__col">
                <h3 class="dims__title">优化后维度得分</h3>
                <DimensionBars v-if="evaluated" :items="report.dimensionScoresAfter?.items ?? []" />
                <p v-else class="dims__pending">
                  点击「重新评估」后展示。评估会把优化稿重新做一次结构化分析，
                  再按与优化前完全相同的规则重算——两侧可比，但不承诺分数必然提升。
                </p>
              </div>
            </div>

            <p v-if="evaluated" class="card__note">
              提示：优化改的是表述、不改变事实，分数持平或略降都属正常；分数只反映简历与岗位描述的
              信息匹配程度，不代表录用概率。
            </p>
          </el-card>

          <!-- ③ 岗位要求分析 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>岗位要求分析</h2>
                <span class="card__sub">来自本次优化所依据的岗位分析（v{{ report.jobVersionNo }}）</span>
              </div>
            </template>
            <template v-if="requirements">
              <p v-if="requirements.summary" class="req__summary">{{ requirements.summary }}</p>
              <div class="req__row">
                <span class="req__label">必备技能</span>
                <div class="req__tags">
                  <el-tag v-for="skill in requirements.requiredSkills ?? []" :key="skill" size="small" effect="light">
                    {{ skill }}
                  </el-tag>
                  <span v-if="(requirements.requiredSkills ?? []).length === 0" class="req__empty">未提取到</span>
                </div>
              </div>
              <div class="req__row">
                <span class="req__label">加分技能</span>
                <div class="req__tags">
                  <el-tag v-for="skill in requirements.preferredSkills ?? []" :key="skill" size="small" effect="plain">
                    {{ skill }}
                  </el-tag>
                  <span v-if="(requirements.preferredSkills ?? []).length === 0" class="req__empty">未提取到</span>
                </div>
              </div>
              <div class="req__row">
                <span class="req__label">技术栈</span>
                <div class="req__tags">
                  <el-tag v-for="tech in requirements.technologyStack ?? []" :key="tech" size="small" effect="plain">
                    {{ tech }}
                  </el-tag>
                  <span v-if="(requirements.technologyStack ?? []).length === 0" class="req__empty">未提取到</span>
                </div>
              </div>
              <div class="req__row">
                <span class="req__label">职责</span>
                <ul class="req__list">
                  <li v-for="(item, index) in requirements.responsibilities ?? []" :key="index">
                    {{ item }}
                  </li>
                </ul>
              </div>
              <div class="req__row req__row--plain">
                <span class="req__label">学历 / 经验</span>
                <span class="req__value">
                  {{ requirements.educationRequirement || '未提及' }} ·
                  {{ requirements.experienceRequirement || '未提及' }}
                </span>
              </div>
            </template>
            <p v-else class="detail-col__empty">岗位要求为空。</p>
          </el-card>

          <!-- ④ 简历优势 -->
          <el-card shadow="never" class="card">
            <template #header>
              <h2>简历优势</h2>
            </template>
            <ul v-if="advantages.length > 0" class="plain-list">
              <li v-for="(item, index) in advantages" :key="index">{{ item }}</li>
            </ul>
            <p v-else class="detail-col__empty">暂无归纳结果。</p>
          </el-card>

          <!-- ⑤ 岗位要求 × 经历对应 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>岗位要求与简历经历的对应关系</h2>
                <span class="card__sub">逐条对应岗位要求与简历证据</span>
              </div>
            </template>
            <div class="detail-grid">
              <div class="detail-col">
                <h3 class="detail-col__title detail-col__title--ok">
                  已匹配（{{ matchedItems.length }}）
                </h3>
                <div v-for="(item, index) in matchedItems" :key="`m${index}`" class="detail-item">
                  <span class="detail-item__text">{{ item.text }}</span>
                  <el-tag v-if="item.dimension" size="small" effect="plain">
                    {{ dimensionLabel(item.dimension) }}
                  </el-tag>
                </div>
                <p v-if="matchedItems.length === 0" class="detail-col__empty">暂无</p>
              </div>

              <div class="detail-col">
                <h3 class="detail-col__title detail-col__title--warn">
                  部分匹配（{{ partialItems.length }}）
                </h3>
                <div v-for="(item, index) in partialItems" :key="`p${index}`" class="detail-item">
                  <span class="detail-item__text">{{ item.text }}</span>
                  <el-tag v-if="item.dimension" size="small" effect="plain">
                    {{ dimensionLabel(item.dimension) }}
                  </el-tag>
                </div>
                <p v-if="partialItems.length === 0" class="detail-col__empty">暂无</p>
              </div>
            </div>
          </el-card>

          <!-- ⑥ 能力差距与待确认问题 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>能力差距与待确认问题</h2>
                <span class="card__sub">差距只说明「简历没写上」，不等于「不具备」</span>
              </div>
            </template>

            <el-alert class="gap__note" type="info" :closable="false" show-icon>
              差额反映的是简历与岗位描述之间的<strong>信息缺口</strong>，不代表你不具备该能力。
              把相关经历补充进简历后，差距项会随之变化。
            </el-alert>

            <div v-for="(item, index) in missingItems" :key="`gap${index}`" class="gap-item">
              <div class="gap-item__text">{{ item.text }}</div>
              <div v-if="item.dimension" class="gap-item__dim">
                影响维度：{{ dimensionLabel(item.dimension) }}
              </div>
            </div>
            <p v-if="missingItems.length === 0" class="detail-col__empty">
              没有发现明显的差距项。
            </p>

            <div v-if="gapQuestions.length > 0" class="questions">
              <h3 class="questions__title">待确认问题（{{ gapQuestions.length }}）</h3>
              <p class="questions__note">
                以下信息简历中未提供，请确认后再补充——系统<strong>不会替你编造</strong>；
                补充真实信息后重新上传简历并再次优化，结果会更贴合岗位。
              </p>
              <ol class="questions__list">
                <li v-for="(question, index) in gapQuestions" :key="`q${index}`">
                  {{ question }}
                </li>
              </ol>
            </div>
          </el-card>

          <!-- ⑦ 逐项修改说明 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>逐项修改说明</h2>
                <span class="card__sub">共 {{ changes.length }} 处改动，逐条给出原因与前后对照</span>
              </div>
            </template>
            <ChangeCompare :changes="changes" />
          </el-card>

          <!-- ⑧ 简历对比与编辑 -->
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>优化稿</h2>
                <span class="card__sub">
                  编辑只影响「你的当前稿」，模型原稿与原始简历都不会被覆盖
                </span>
              </div>
            </template>

            <div class="editor">
              <div class="editor__original">
                <h3 class="editor__title">原始简历（只读）</h3>
                <p class="editor__hint">
                  原始文件与提取文本不会被任何优化操作修改；此处的文本仅供对照。
                </p>
                <el-button
                  size="small"
                  :disabled="resumeMissing"
                  @click="router.push('/resumes')"
                >
                  查看简历管理
                </el-button>
                <el-tag v-if="resumeMissing" class="editor__missing" type="info" size="small" effect="plain">
                  源简历已删除
                </el-tag>
              </div>
              <div class="editor__draft">
                <h3 class="editor__title">
                  你的当前稿
                  <el-tag v-if="report.editedContent === null" size="small" effect="plain">
                    模型原稿
                  </el-tag>
                  <el-tag v-else size="small" effect="plain" type="success">
                    已编辑
                  </el-tag>
                </h3>
                <el-input
                  v-model="draft"
                  type="textarea"
                  :rows="16"
                  maxlength="20000"
                  resize="vertical"
                  class="editor__textarea"
                />
                <div class="editor__actions">
                  <el-button
                    type="primary"
                    :disabled="!dirty"
                    :loading="saving"
                    @click="save"
                  >
                    保存编辑
                  </el-button>
                  <el-button :icon="Refresh" :loading="evaluating" @click="evaluate">
                    重新评估
                  </el-button>
                  <el-button :icon="Download" :loading="exporting" @click="exportDocx">
                    导出 DOCX
                  </el-button>
                  <span v-if="dirty" class="editor__dirty">有未保存的修改</span>
                </div>
              </div>
            </div>
          </el-card>

          <p class="footnote">
            匹配分数反映简历与岗位描述之间的信息匹配程度，不代表录用概率或招聘结果预测；
            优化只改表述、不改变事实。
          </p>
        </div>

        <!-- 右栏 -->
        <div class="layout__side">
          <el-card shadow="never" class="card">
            <template #header>
              <h2>优化依据</h2>
            </template>
            <dl class="basis">
              <dt>使用简历</dt>
              <dd>{{ report.resumeName }} · v{{ report.resumeVersionNo }}</dd>
              <dt>目标岗位</dt>
              <dd>
                {{ report.jobName }}<template v-if="report.companyName"> · {{ report.companyName }}</template>
                · v{{ report.jobVersionNo }}
              </dd>
              <dt>优化前匹配记录</dt>
              <dd>#{{ report.matchId }}</dd>
              <dt>评分规则</dt>
              <dd>{{ report.dimensionScores?.ruleVersion }}（权重 45 / 25 / 15 / 10 / 5）</dd>
              <dt>模型</dt>
              <dd>{{ report.modelName }}</dd>
              <dt>创建时间</dt>
              <dd>{{ formatTime(report.createdAt) }}</dd>
              <dt>最近更新</dt>
              <dd>{{ formatTime(report.updatedAt) }}</dd>
            </dl>
          </el-card>

          <el-card shadow="never" class="card">
            <template #header>
              <div class="card__head">
                <h2>历史版本</h2>
                <span class="card__sub">{{ editHistory.length }} 版</span>
              </div>
            </template>
            <template v-if="editHistory.length > 0">
              <div v-for="(snapshot, index) in [...editHistory].reverse()" :key="index" class="history">
                <div class="history__head">
                  <span class="history__time">{{ formatTime(snapshot.savedAt) }}</span>
                  <span class="history__actions">
                    <el-button link type="primary" size="small" @click="openCompare(snapshot)">
                      对比
                    </el-button>
                    <el-button link type="primary" size="small" @click="fillHistory(snapshot)">
                      回填
                    </el-button>
                  </span>
                </div>
                <p class="history__preview">{{ (snapshot.content || '').slice(0, 80) }}…</p>
              </div>
            </template>
            <p v-else class="source__note">
              还没有编辑记录。保存编辑后，被替换的旧稿会自动留在这里（最多 10 版）。
            </p>
          </el-card>

          <el-card shadow="never" class="card">
            <template #header>
              <h2>关联资源</h2>
            </template>
            <div class="source">
              <div class="source__row">
                <span class="source__label">简历</span>
                <el-button link :disabled="resumeMissing" @click="router.push('/resumes')">
                  {{ report.resumeName }}
                </el-button>
                <el-tag v-if="resumeMissing" type="info" size="small" effect="plain">
                  已删除
                </el-tag>
              </div>
              <div class="source__row">
                <span class="source__label">岗位</span>
                <el-button link :disabled="jobMissing" @click="router.push('/jobs')">
                  {{ report.jobName }}
                </el-button>
                <el-tag v-if="jobMissing" type="info" size="small" effect="plain">
                  已删除
                </el-tag>
              </div>
              <p v-if="resumeMissing || jobMissing" class="source__note">
                源资源已删除，本优化记录作为历史仍然保留。
              </p>
            </div>
          </el-card>

          <el-card shadow="never" class="card">
            <template #header>
              <h2>下一步</h2>
            </template>
            <div class="next">
              <el-button :icon="MagicStick" class="next__button" @click="router.push('/optimizations')">
                再优化一版
              </el-button>
              <el-button
                class="next__button"
                @click="router.push({ name: 'interviews', query: { optimizationId } })"
              >
                用这份简历模拟面试
              </el-button>
            </div>
          </el-card>
        </div>
      </div>
    </StateBlock>

    <!-- M13：版本对比（行内差异标注，纯前端 LCS，不调后端） -->
    <el-dialog v-model="compareVisible" title="版本对比" width="720px">
      <div class="compare__legend">
        <span>历史版本 · {{ compareSnapshot ? formatTime(compareSnapshot.savedAt) : '' }}</span>
        <span class="compare__legend-arrow">→ 当前稿</span>
        <span class="compare__chips">
          <span class="compare__chip compare__chip--del">- 删除</span>
          <span class="compare__chip compare__chip--add">+ 新增</span>
        </span>
      </div>
      <div class="compare__body">
        <p
          v-for="(line, index) in compareDiff"
          :key="index"
          class="compare__line"
          :class="`compare__line--${line.type}`"
        >
          <span class="compare__sign">{{ line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' ' }}</span>
          <span class="compare__text">{{ line.text || ' ' }}</span>
        </p>
      </div>
      <template #footer>
        <el-button @click="compareVisible = false">
          关闭
        </el-button>
        <el-button type="primary" @click="fillHistoryFromCompare">
          回填此版本到编辑器
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* M16：任务进行中 / 失败态的卡片布局 */
.task-card__foot {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

/* V1.13：失败任务的校验问题明细 */
.task-card__issues {
  margin-top: 12px;
}

.task-card__issue-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-card__issue-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-700, #333);
}

.page-head {
  margin-bottom: 16px;
}

.page-head h1 {
  font-size: 22px;
  margin: 4px 0;
}

.page-head__desc {
  font-size: 13px;
  color: var(--ink-500);
}

.page-head__back {
  padding: 0;
}

/* ---- 两栏布局 ---- */
.layout {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 16px;
  align-items: start;
}

.layout__main,
.layout__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: 1fr;
  }
}

.card {
  border-radius: var(--radius);
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

.card__head h2 {
  font-size: 17px;
}

.card__sub {
  font-size: 12px;
  color: var(--ink-500);
}

.card__note {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink-500);
}

/* ---- 分数对比 ---- */
.score-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-around;
  gap: 16px;
}

.score-row__side {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.score-row__arrow {
  align-self: center;
  font-size: 22px;
  color: var(--ink-400);
}

.score-row__pending {
  width: 132px;
  height: 132px;
  border: 2px dashed var(--line);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.score-row__pending-text {
  font-size: 14px;
  color: var(--ink-400);
}

.score-row__label {
  font-size: 12px;
  color: var(--ink-500);
}

.dims {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}

@media (max-width: 900px) {
  .dims {
    grid-template-columns: 1fr;
  }
}

.dims__title {
  font-size: 13px;
  margin-bottom: 10px;
  color: var(--ink-500);
}

.dims__pending {
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-400);
}

/* ---- 岗位要求 ---- */
.req__summary {
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-700);
  margin-bottom: 14px;
}

.req__row {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px dashed var(--line);
}

.req__row--plain {
  align-items: center;
}

.req__label {
  flex: 0 0 76px;
  font-size: 12px;
  color: var(--ink-400);
  padding-top: 2px;
}

.req__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.req__value {
  font-size: 13px;
  color: var(--ink-700);
}

.req__list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-700);
}

.req__empty {
  font-size: 12.5px;
  color: var(--ink-400);
}

/* ---- 通用列表 ---- */
.plain-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  color: var(--ink-700);
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}

.detail-col__title {
  font-size: 14px;
  margin-bottom: 10px;
}

.detail-col__title--ok {
  color: var(--ok);
}

.detail-col__title--warn {
  color: var(--warn);
}

.detail-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  margin-bottom: 8px;
}

.detail-item__text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700);
}

.detail-col__empty {
  font-size: 13px;
  color: var(--ink-400);
}

/* ---- 差距与待确认问题 ---- */
.gap__note {
  margin-bottom: 14px;
}

.gap-item {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--danger);
  border-radius: var(--radius-sm);
  margin-bottom: 10px;
}

.gap-item__text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700);
}

.gap-item__dim {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-400);
}

.questions {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--brand-100, var(--line));
  border-radius: var(--radius-sm);
  background: var(--brand-50);
}

.questions__title {
  font-size: 14px;
}

.questions__note {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-500);
}

.questions__list {
  margin: 10px 0 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  color: var(--ink-700);
}

/* ---- 编辑器 ---- */
.editor {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 20px;
}

@media (max-width: 900px) {
  .editor {
    grid-template-columns: 1fr;
  }
}

.editor__title {
  font-size: 13px;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.editor__hint {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
  margin-bottom: 10px;
}

.editor__missing {
  margin-left: 8px;
}

.editor__textarea :deep(textarea) {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
}

.editor__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.editor__dirty {
  font-size: 12px;
  color: var(--warn);
}

.footnote {
  font-size: 12px;
  color: var(--ink-400);
  text-align: center;
}

/* ---- 右栏 ---- */
.basis {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
}

.basis dt {
  color: var(--ink-400);
  font-size: 12px;
  margin-top: 10px;
}

.basis dt:first-child {
  margin-top: 0;
}

.basis dd {
  margin: 2px 0 0;
  color: var(--ink-700);
  word-break: break-word;
}

.history {
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
}

.history:last-child {
  border-bottom: none;
}

.history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history__time {
  font-size: 12px;
  color: var(--ink-400);
}

.history__preview {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-500);
  word-break: break-word;
}

.source__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
}

.source__label {
  flex: 0 0 38px;
  color: var(--ink-400);
  font-size: 12px;
}

.source__note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-400);
}

.next {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.next__button {
  margin-left: 0;
  width: 100%;
}

/* ---- M13 版本对比 ---- */
.history__actions {
  display: inline-flex;
  gap: 2px;
}

.compare__legend {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-500);
  margin-bottom: 10px;
}

.compare__legend-arrow {
  color: var(--ink-400);
}

.compare__chips {
  margin-left: auto;
  display: flex;
  gap: 6px;
}

.compare__chip {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
}

.compare__chip--del {
  color: var(--danger);
  background: #fdecec;
}

.compare__chip--add {
  color: #3f8f4f;
  background: #eef7ee;
}

.compare__body {
  max-height: 52vh;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 10px 0;
  background: var(--bg);
}

.compare__line {
  display: flex;
  gap: 8px;
  padding: 1px 12px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}

.compare__line--del {
  background: #fdecec;
  color: #c45656;
}

.compare__line--add {
  background: #eef7ee;
  color: #3f8f4f;
}

.compare__sign {
  flex: 0 0 12px;
  color: var(--ink-400);
  user-select: none;
}

.compare__text {
  flex: 1;
  min-width: 0;
}
</style>
