<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

import { analyzeJob, createJob } from '@/api/job'
import { createOptimization, listOptimizations } from '@/api/optimization'
import { resolveSubmitFeedback, resolveSubmitSuccess } from '@/utils/optimizationSubmit'
import AiWaiting from '@/components/AiWaiting.vue'
import PaginationBar from '@/components/PaginationBar.vue'
import ResourcePicker from '@/components/ResourcePicker.vue'
import StateBlock from '@/components/StateBlock.vue'

/**
 * 简历定向优化（《前端原型设计》§5.7，接口《API接口设计》§6A.1–6A.2）。
 *
 * 列表 + 新建弹窗。弹窗的目标岗位有两种选法：
 * - 选择已有岗位：与简历选择器同构，未分析/已过期**就地拦截**、不发出请求；
 * - 手动输入 JD：提交时由前端**三步编排**——POST /api/jobs（建岗位）
 *   → POST /api/jobs/{id}/analyze（分析）→ POST /api/optimizations（优化），
 *   每步独立超时、进度可见、失败可定位。岗位一旦创建成功即记住 ID，
 *   重试时不重复建岗位（后端不提供"临时 JD"入参，见 §6A.1）。
 *
 * 创建优化 M16 起为异步任务：等待态在弹窗内呈现；失败时区分「未创建 /
 * 已创建但执行失败 / 结果未知」，已创建但失败的由后端带回 optimizationId，
 * 可直接打开详情查看原因并重试；成功后跳转工作台。
 */
const PAGE_SIZE = 10

const router = useRouter()
const route = useRoute()

/* ---------------------------------------------------------------- 列表 */

const loading = ref(false)
const loadError = ref('')
const records = ref([])
const total = ref(0)
const page = ref(1)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await listOptimizations({ page: page.value, size: PAGE_SIZE })
    records.value = data.records
    total.value = data.total
  }
  catch (error) {
    loadError.value = error.message
  }
  finally {
    loading.value = false
  }
}

function changePage(next) {
  page.value = next
  load()
}

const formatTime = (iso) => dayjs(iso).format('YYYY-MM-DD HH:mm')

/** 分数展示去掉尾零（76.50 → 76.5）；分数本身由后端计算，前端不加工 */
const displayScore = (score) => (score === null || score === undefined ? '待评估' : Number(score))

/** M16：任务状态标签（列表含 PENDING/PROCESSING/FAILED 的任务行） */
const TASK_TAG_LABELS = { PENDING: '排队中', PROCESSING: '生成中', FAILED: '生成失败' }

const taskTagText = (status) => TASK_TAG_LABELS[status] ?? status

const taskTagType = (status) => {
  if (status === 'FAILED') {
    return 'danger'
  }
  if (status === 'PENDING' || status === 'PROCESSING') {
    return 'primary'
  }
  return 'info'
}

/** 分数语义色标签（§5.7）：≥80 绿、60–79 蓝、<60 橙；null（未评估）用中性色 */
function scoreType(score) {
  if (score === null || score === undefined) {
    return 'info'
  }
  if (score >= 80) {
    return 'success'
  }
  if (score >= 60) {
    return 'primary'
  }
  return 'warning'
}

function openDetail(item) {
  router.push({ name: 'optimization-detail', params: { id: item.id } })
}

onMounted(() => {
  load()
  // 工作台快捷操作与工作流卡「粘贴 JD」带 `?create=1` 进入：直接打开新建弹窗
  if (route.query.create) {
    openCreate()
  }
})

/* ---------------------------------------------------------------- 新建优化 */

const dialogVisible = ref(false)
/** 目标岗位的选法：'existing' 选择已有岗位 / 'manual' 手动输入 JD */
const jobMode = ref('existing')

const resumeId = ref(null)
const jobId = ref(null)
const resumePicker = ref()
const jobPicker = ref()

/** 手动输入 JD 的表单 */
const manualJob = ref({ jobName: '', companyName: '', description: '' })

/** 本次弹窗内已创建的岗位 ID（重试时不重复创建；关闭弹窗即重置） */
const createdJobId = ref(null)

const submitting = ref(false)
/** 提交进度步骤：null / 'creating-job' / 'analyzing-job' / 'optimizing' */
const step = ref(null)
/** 提交失败信息 { code, message, failedStep }；null 表示无 */
const submitError = ref(null)

/** 简历选择器被就地拦截（未分析）时禁止提交；岗位选择器仅在"选已有岗位"模式下生效 */
const blockReason = computed(() => {
  const resumeBlock = resumePicker.value?.blockReason ?? null
  const jobBlock = jobMode.value === 'existing' ? (jobPicker.value?.blockReason ?? null) : null
  return resumeBlock ?? jobBlock ?? null
})

/** 手动输入模式：岗位名称与 JD 文本必填（与 POST /api/jobs 的校验一致） */
const manualValid = computed(
  () => manualJob.value.jobName.trim().length > 0 && manualJob.value.description.trim().length > 0,
)

const canSubmit = computed(() => {
  if (submitting.value || resumeId.value === null || blockReason.value) {
    return false
  }
  return jobMode.value === 'existing' ? jobId.value !== null : manualValid.value
})

function openCreate() {
  resumeId.value = null
  jobId.value = null
  jobMode.value = 'existing'
  manualJob.value = { jobName: '', companyName: '', description: '' }
  createdJobId.value = null
  submitError.value = null
  step.value = null
  dialogVisible.value = true
}

/** 当前步骤的等待态文案 */
const waitingTitle = computed(() => {
  if (step.value === 'creating-job') {
    return '正在创建岗位…'
  }
  if (step.value === 'analyzing-job') {
    return '岗位已创建，正在分析岗位要求…'
  }
  return 'AI 正在生成优化稿…'
})

const waitingHint = computed(() => {
  if (step.value === 'creating-job') {
    return '把输入的岗位名称与 JD 保存为一条岗位记录，通常只需一两秒。'
  }
  if (step.value === 'analyzing-job') {
    return '提取岗位要求与技能项，通常需要 10–30 秒;完成后自动开始生成优化稿。'
  }
  return '先完成匹配分析、再按岗位要求改写简历，通常需要 30–90 秒;中途关闭页面不会影响结果。'
})

async function submit() {
  if (!canSubmit.value) {
    return
  }
  submitting.value = true
  submitError.value = null
  try {
    let targetJobId = jobId.value
    if (jobMode.value === 'manual') {
      // 步骤 1：建岗位（已建过则跳过——重试时不重复创建）
      if (createdJobId.value === null) {
        step.value = 'creating-job'
        const job = await createJob({
          jobName: manualJob.value.jobName.trim(),
          companyName: manualJob.value.companyName.trim() || null,
          description: manualJob.value.description.trim(),
        })
        createdJobId.value = job.id
      }
      targetJobId = createdJobId.value
      // 步骤 2：分析岗位（每次重试都重跑：失败重试时分析可能未完成）
      step.value = 'analyzing-job'
      await analyzeJob(targetJobId)
    }
    // 步骤 3：创建优化（匹配 + 优化生成，2 次模型调用）
    step.value = 'optimizing'
    const data = await createOptimization({ resumeId: resumeId.value, jobId: targetJobId })
    dialogVisible.value = false
    ElMessage.success(resolveSubmitSuccess(data.status))
    router.push({ name: 'optimization-detail', params: { id: data.id } })
  }
  catch (error) {
    // O-16：不能只看错误码就断言“未产生记录”——同步降级失败时后端已留下
    // FAILED 记录并带回 optimizationId；无法确认时提示去列表查看。
    const feedback = resolveSubmitFeedback({ error, failedStep: step.value })
    submitError.value = {
      code: error.code,
      text: feedback.text,
      taskId: feedback.taskId,
    }
  }
  finally {
    submitting.value = false
    step.value = null
  }
}

/** 打开已创建但执行失败的任务（详情页展示失败原因与重试入口） */
function openFailedTask() {
  if (!submitError.value?.taskId) {
    return
  }
  const failedTaskId = submitError.value.taskId
  dialogVisible.value = false
  router.push({ name: 'optimization-detail', params: { id: failedTaskId } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>简历定向优化</h1>
        <p class="page-head__desc">
          按目标岗位改写简历：事实不变、逐项可查，改完可编辑、评估与导出
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">
        新建简历优化
      </el-button>
    </div>

    <el-card shadow="never" class="card">
      <template #header>
        <div class="card__head">
          <h2>优化记录</h2>
          <span class="card__sub">每次优化新增一条记录，用于对比同一岗位下的多版改写</span>
        </div>
      </template>

      <StateBlock
        :loading="loading"
        :error="loadError"
        :empty="records.length === 0"
        @retry="load"
      >
        <template #empty>
          <h3 class="state__title">还没有优化记录</h3>
          <p class="state__text">
            选择一份已分析的简历与一个目标岗位（也可以直接粘贴 JD），系统会先完成匹配分析，
            再按岗位要求改写简历，并逐项说明改了什么、为什么改。
          </p>
          <el-button type="primary" @click="openCreate">
            新建简历优化
          </el-button>
        </template>

        <div class="item-list">
          <div v-for="item in records" :key="item.id" class="item">
            <div class="item__icon">
              <el-icon :size="18"><MagicStick /></el-icon>
            </div>
            <div class="item__main">
              <div class="item__title">
                <span class="item__name">{{ item.jobName }}</span>
                <!-- M16：任务未成功完成时展示状态标签（分数尚未产生） -->
                <template v-if="item.status === 'SUCCESS'">
                  <el-tag :type="scoreType(item.matchScore)" size="small" effect="light">
                    {{ displayScore(item.matchScore) }}
                  </el-tag>
                  <span class="item__arrow">→</span>
                  <el-tag :type="scoreType(item.scoreAfter)" size="small" effect="light">
                    {{ displayScore(item.scoreAfter) }}
                  </el-tag>
                </template>
                <el-tag v-else :type="taskTagType(item.status)" size="small" effect="light">
                  {{ taskTagText(item.status) }}
                </el-tag>
              </div>
              <div class="item__meta">
                <template v-if="item.companyName">{{ item.companyName }} · </template>
                使用「{{ item.resumeName }}」 · v{{ item.versionNo }}
                · {{ formatTime(item.createdAt) }}
              </div>
            </div>
            <div class="item__actions">
              <el-button size="small" @click="openDetail(item)">
                打开工作台
              </el-button>
            </div>
          </div>
        </div>
      </StateBlock>

      <div v-if="!loading && !loadError && records.length > 0" class="card__foot">
        <PaginationBar
          :total="total"
          :page="page"
          :size="PAGE_SIZE"
          @update:page="changePage"
        />
      </div>
    </el-card>

    <!-- 新建优化弹窗：选简历 → 选岗位/输入 JD → 三步编排 → 等待态 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建简历优化"
      width="700px"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <AiWaiting
        v-if="submitting"
        :title="waitingTitle"
        :hint="waitingHint"
        leave-hint="优化在服务器端进行；中途关闭页面不会影响结果，稍后可在列表中查看。"
      />

      <template v-else>
        <p class="dialog__desc">
          简历需先完成 AI 分析；目标岗位可以选择已有岗位，也可以直接粘贴一份 JD（系统会自动建岗位并分析）。
        </p>

        <el-form label-position="top" @submit.prevent>
          <el-form-item label="选择简历" required>
            <ResourcePicker ref="resumePicker" v-model="resumeId" type="resume" />
          </el-form-item>

          <el-form-item label="目标岗位" required>
            <el-tabs v-model="jobMode" class="job-tabs">
              <el-tab-pane label="选择已有岗位" name="existing">
                <ResourcePicker ref="jobPicker" v-model="jobId" type="job" />
              </el-tab-pane>
              <el-tab-pane label="手动输入 JD" name="manual">
                <el-form-item label="岗位名称" required class="manual__field">
                  <el-input
                    v-model="manualJob.jobName"
                    maxlength="100"
                    placeholder="例如：Java 后端开发工程师"
                  />
                </el-form-item>
                <el-form-item label="公司名称（选填）" class="manual__field">
                  <el-input
                    v-model="manualJob.companyName"
                    maxlength="100"
                    placeholder="例如：示例科技"
                  />
                </el-form-item>
                <el-form-item label="JD 文本" required class="manual__field">
                  <el-input
                    v-model="manualJob.description"
                    type="textarea"
                    :rows="7"
                    maxlength="10000"
                    show-word-limit
                    placeholder="从招聘网站复制岗位职责与任职要求，整段粘贴即可"
                  />
                </el-form-item>
                <el-alert class="tip" type="info" :closable="false" show-icon>
                  提交后会先在「岗位管理」创建这条岗位并完成分析，再开始生成优化稿。
                </el-alert>
              </el-tab-pane>
            </el-tabs>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="submitError"
          class="tip"
          type="error"
          :closable="false"
          show-icon
        >
          {{ submitError.text }}
        </el-alert>
        <el-button
          v-if="submitError?.taskId"
          class="tip"
          size="small"
          @click="openFailedTask"
        >
          查看任务
        </el-button>

        <el-alert class="tip" type="info" :closable="false" show-icon>
          <p class="dialog__note">
            优化只改表述、不新增事实：简历中没有的经历、技能与数字不会被补写，
            岗位要求而简历未提供的信息会整理成「待确认问题」供你补充。
          </p>
        </el-alert>
      </template>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">
          取消
        </el-button>
        <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">
          开始优化
        </el-button>
      </template>
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

.page-head h1 {
  font-size: 22px;
  margin-bottom: 4px;
}

.page-head__desc {
  font-size: 13px;
  color: var(--ink-500);
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
}

.card__head h2 {
  font-size: 17px;
}

.card__sub {
  font-size: 13px;
  color: var(--ink-500);
}

.card__foot {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

/* ---- 列表项 ---- */
.item-list {
  display: flex;
  flex-direction: column;
}

.item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 12px;
  border-radius: var(--radius-sm);
  transition: background 0.15s;
}

.item + .item {
  border-top: 1px solid var(--line);
}

.item:hover {
  background: var(--bg);
}

.item__icon {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--brand-50);
  color: var(--brand-600);
}

.item__main {
  flex: 1;
  min-width: 0;
}

.item__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-900);
}

.item__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item__arrow {
  color: var(--ink-400);
  font-size: 12px;
}

.item__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.item__actions {
  flex: 0 0 auto;
}

/* ---- 弹窗 ---- */
.dialog__desc {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--ink-500);
}

.dialog__note {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}

.job-tabs {
  width: 100%;
}

.job-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.manual__field {
  margin-bottom: 12px;
}

.tip {
  margin-top: 12px;
}

.state__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-900);
}

.state__text {
  max-width: 460px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-500);
  margin-bottom: 4px;
}
</style>
