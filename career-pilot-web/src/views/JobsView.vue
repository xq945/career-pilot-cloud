<script setup>
import { h, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Briefcase, Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

import { createJob, getJob, listJobs, removeJob, updateJob, updateJobStatus } from '@/api/job'
import PaginationBar from '@/components/PaginationBar.vue'
import StateBlock from '@/components/StateBlock.vue'

/**
 * 岗位管理（《前端原型设计》§5.5，接口《API接口设计》5.1–5.5）。
 *
 * 页面内容：列表（加载 / 空 / 错误 / 正常四态）+ 新建/编辑对话框 + 删除二次确认
 * + 投递入口（M19：岗位填了投递链接时列表出现「投递」按钮，新标签页打开该链接）
 * + 求职进度（M21：列表行内下拉直接切换，走专用的 §5.8 状态接口，不经编辑弹窗）。
 * 与简历管理同构，差异在于：
 * - 岗位是**手输文本**（无文件上传），因此无类型/大小预检；
 * - **可编辑**，列表项**不返回 description**，编辑回填需先取详情（§5.3）。
 *
 * V1.25（M20）：本页的分析入口、三态标签与编辑弹窗的过期警告条已删除，只做岗位 CRUD——
 * 描述变更对分析的影响改由定向优化链路就地拦截呈现（§5.7），分析入口在分析页自身与工作台/优化链路。
 * 时间字段用 dayjs 解析（API §1：精度不保证，禁止字符串截取）。
 */
const PAGE_SIZE = 10

/**
 * 求职进度（M21）：与后端 `Job.PROGRESS_*` 常量一一对应，顺序即下拉展示顺序。
 * 不设状态机（用户可任意切换/回退）；`type` 仅驱动文字颜色，不改变控件形态。
 */
const PROGRESS_OPTIONS = [
  { value: 'PENDING', label: '待投递', type: 'pending' },
  { value: 'APPLIED', label: '已投递', type: 'applied' },
  { value: 'WRITTEN', label: '笔试', type: 'progress' },
  { value: 'INTERVIEW_1', label: '一面', type: 'progress' },
  { value: 'INTERVIEW_2', label: '二面', type: 'progress' },
  { value: 'HR', label: 'HR 面', type: 'progress' },
  { value: 'OFFER', label: 'Offer', type: 'offer' },
  { value: 'REJECTED', label: '已拒绝', type: 'rejected' },
]

const progressOf = (item) => PROGRESS_OPTIONS.find((option) => option.value === item.progressStatus)
  ?? PROGRESS_OPTIONS[0]

const progressLabel = (value) => PROGRESS_OPTIONS.find((option) => option.value === value)?.label ?? value

/** 投递入口（M19）：新标签页打开用户填写的投递网页；noopener/noreferrer 按外部站点对待 */
function openApply(item) {
  window.open(item.applyUrl, '_blank', 'noopener,noreferrer')
}

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
    const data = await listJobs({ page: page.value, size: PAGE_SIZE })
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

onMounted(load)

/* ---------------------------------------------------------------- 新建 / 编辑（同一个弹窗） */

const dialogVisible = ref(false)
/** null = 新建模式；非 null = 编辑该 id 的岗位 */
const editingId = ref(null)
/** 编辑模式下取详情回填期间的加载态（列表接口不含 description） */
const editingLoading = ref(false)
const submitting = ref(false)
const formRef = ref()
const formError = ref('')

const form = reactive({ companyName: '', jobName: '', description: '', applyUrl: '' })

/** 投递链接选填：留空放行，填了就必须是 http/https（与后端 normalizeApplyUrl 同一口径） */
function validateApplyUrl(rule, value, callback) {
  const url = (value || '').trim()
  if (url && !/^https?:\/\//i.test(url)) {
    callback(new Error('投递链接需以 http:// 或 https:// 开头'))
    return
  }
  callback()
}

const formRules = {
  jobName: [
    { required: true, message: '请输入岗位名称', trigger: 'blur' },
    { max: 100, message: '岗位名称不能超过 100 个字符', trigger: 'blur' },
  ],
  description: [
    { required: true, message: '请粘贴岗位描述', trigger: 'blur' },
    { max: 10000, message: '岗位描述不能超过 10000 个字符', trigger: 'blur' },
  ],
  companyName: [
    { max: 100, message: '公司名称不能超过 100 个字符', trigger: 'blur' },
  ],
  applyUrl: [
    { max: 500, message: '投递链接不能超过 500 个字符', trigger: 'blur' },
    { validator: validateApplyUrl, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  editingLoading.value = false
  form.companyName = ''
  form.jobName = ''
  form.description = ''
  form.applyUrl = ''
  formError.value = ''
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** 编辑：列表不含 description，先取详情回填（§5.2/§5.3） */
async function openEdit(item) {
  editingId.value = item.id
  formError.value = ''
  form.companyName = ''
  form.jobName = ''
  form.description = ''
  form.applyUrl = ''
  dialogVisible.value = true
  editingLoading.value = true
  try {
    const data = await getJob(item.id)
    form.companyName = data.companyName || ''
    form.jobName = data.jobName
    form.description = data.description
    form.applyUrl = data.applyUrl || ''
  }
  catch (error) {
    dialogVisible.value = false
    ElMessage.error(error.message || '加载岗位详情失败')
  }
  finally {
    editingLoading.value = false
    nextTick(() => formRef.value?.clearValidate())
  }
}

async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  formError.value = ''
  const isEditing = editingId.value !== null
  try {
    // 全量更新语义（§5.4）：各字段总是整份提交；空公司名/空链接归一为 null（后端同样处理）
    const payload = {
      companyName: form.companyName.trim() || null,
      jobName: form.jobName.trim(),
      description: form.description,
      applyUrl: form.applyUrl.trim() || null,
    }
    if (isEditing) {
      await updateJob(editingId.value, payload)
      dialogVisible.value = false
      await load()
      ElMessage.success('岗位已更新')
    }
    else {
      const data = await createJob(payload)
      dialogVisible.value = false
      page.value = 1 // 列表按 created_at 倒序，新岗位在第 1 页首位
      await load()
      ElMessage.success(`「${data.jobName}」创建成功，可用于简历定向优化`)
    }
  }
  catch (error) {
    // 失败保持弹窗打开、可修改后重试；40001 的字段级提示由 el-form 与全局文案共同呈现
    formError.value = error.message || '保存失败，请稍后重试'
  }
  finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------- 求职进度（M21） */

/** 正在提交状态的岗位 id：期间禁用该行下拉，避免连点产生乱序请求 */
const statusSavingId = ref(null)

/**
 * 行内切换进度（§5.8）：先乐观更新再请求，失败回滚并提示。
 *
 * 不整页 reload 的原因：reload 会把列表滚回顶部、丢失用户的位置；
 * 接口返回的详情足够更新本行（progressStatus / updatedAt），其余字段不受影响。
 */
async function handleStatusChange(item, value) {
  const previous = item.progressStatus
  if (value === previous) {
    return
  }
  item.progressStatus = value
  statusSavingId.value = item.id
  try {
    const data = await updateJobStatus(item.id, value)
    item.progressStatus = data.progressStatus
    item.updatedAt = data.updatedAt
    ElMessage.success(`「${item.jobName}」进度已更新为「${progressLabel(data.progressStatus)}」`)
  }
  catch (error) {
    item.progressStatus = previous
    ElMessage.error(error.message || '状态更新失败，请稍后重试')
  }
  finally {
    statusSavingId.value = null
  }
}

/* ---------------------------------------------------------------- 删除 */

/** 删除确认：写清两条后果（§5.5、原型 jobs.html），用 VNode 渲染（转义用户输入，不拼 HTML） */
function confirmDelete(item) {
  return ElMessageBox.confirm(
    h('div', [
      h('p', `即将删除「${item.jobName}」。`),
      h('ul', { class: 'confirm-list' }, [
        h('li', '岗位不再出现在列表中，也无法用于新的匹配与面试。'),
        h('li', '由该岗位产生的分析与匹配记录会保留，便于追溯历史结论。'),
      ]),
    ]),
    '删除这个岗位？',
    {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: 'el-button--danger',
    },
  )
}

async function handleDelete(item) {
  try {
    await confirmDelete(item)
  }
  catch {
    return // 用户取消
  }
  try {
    await removeJob(item.id)
    ElMessage.success(`「${item.jobName}」已删除`)
    // 当前页删空且不在第 1 页时回退一页，避免落在空页
    if (records.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  }
  catch (error) {
    ElMessage.error(error.message)
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>岗位管理</h1>
        <p class="page-head__desc">
          粘贴目标岗位的招聘原文，用于简历定向优化与模拟面试出题
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">
        新建岗位
      </el-button>
    </div>

    <el-card shadow="never" class="card">
      <template #header>
        <div class="card__head">
          <h2>我的目标岗位</h2>
          <span v-if="total > 0" class="card__sub">共 {{ total }} 个</span>
        </div>
      </template>

      <StateBlock
        :loading="loading"
        :error="loadError"
        :empty="records.length === 0"
        @retry="load"
      >
        <template #empty>
          <h3 class="state__title">还没有目标岗位</h3>
          <p class="state__text">
            把想投递的岗位 JD 原文粘贴进来，用于简历定向优化与模拟面试出题。
          </p>
          <el-button type="primary" @click="openCreate">
            新建岗位
          </el-button>
        </template>

        <div class="item-list">
          <div v-for="item in records" :key="item.id" class="item">
            <div class="item__icon">
              <el-icon :size="18"><Briefcase /></el-icon>
            </div>
            <div class="item__main">
              <div class="item__title">
                <span class="item__name">{{ item.jobName }}</span>
              </div>
              <div class="item__meta">
                <template v-if="item.companyName">{{ item.companyName }} · </template>
                创建于 {{ formatTime(item.createdAt) }} · 更新于 {{ formatTime(item.updatedAt) }}
              </div>
            </div>
            <div class="item__status">
              <el-select
                :model-value="item.progressStatus"
                class="status-select"
                :class="`status-select--${progressOf(item).type}`"
                size="small"
                :disabled="statusSavingId === item.id"
                aria-label="求职进度"
                @change="(value) => handleStatusChange(item, value)"
              >
                <el-option
                  v-for="option in PROGRESS_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
            <div class="item__actions">
              <el-button v-if="item.applyUrl" size="small" @click="openApply(item)">
                投递
              </el-button>
              <el-button size="small" @click="openEdit(item)">
                编辑
              </el-button>
              <el-button size="small" type="danger" plain @click="handleDelete(item)">
                删除
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

    <!-- 新建 / 编辑对话框（同一表单，标题随模式切换） -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建岗位' : '编辑岗位'"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        v-loading="editingLoading"
        :model="form"
        :rules="formRules"
        label-position="top"
        @submit.prevent
      >
        <div class="form-grid">
          <el-form-item label="公司名称" prop="companyName">
            <el-input
              v-model="form.companyName"
              maxlength="100"
              placeholder="选填，如：示例科技"
            />
          </el-form-item>
          <el-form-item label="岗位名称" prop="jobName" required>
            <el-input
              v-model="form.jobName"
              maxlength="100"
              placeholder="如：Java 后端开发实习生"
            />
          </el-form-item>
        </div>

        <!-- 投递链接：必须排在 form-grid 之后——e2e 脚本按索引填弹窗前两个输入框（0=公司名、1=岗位名） -->
        <el-form-item label="投递链接" prop="applyUrl">
          <el-input
            v-model="form.applyUrl"
            maxlength="500"
            placeholder="选填，如 https://www.example.com/job/12345；填写后列表会出现「投递」按钮"
          />
        </el-form-item>

        <el-form-item label="岗位描述" prop="description" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="9"
            maxlength="10000"
            show-word-limit
            placeholder="直接粘贴招聘网站的 JD 原文即可，无需整理格式"
          />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="formError"
        class="tip"
        type="error"
        :closable="false"
        show-icon
      >
        {{ formError }}
      </el-alert>

      <template #footer>
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">
          保存
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

.item__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.item__actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ---- 求职进度下拉（M21）：宽度固定，避免切换前后行内元素跳动 ---- */
.item__status {
  flex: 0 0 auto;
}

.status-select {
  width: 108px;
}

.status-select :deep(.el-select__selected-item) {
  font-weight: 500;
}

.status-select--pending :deep(.el-select__selected-item) {
  color: var(--ink-500);
}

.status-select--applied :deep(.el-select__selected-item) {
  color: var(--info);
}

.status-select--progress :deep(.el-select__selected-item) {
  color: var(--warn);
}

.status-select--offer :deep(.el-select__selected-item) {
  color: var(--ok);
}

.status-select--rejected :deep(.el-select__selected-item) {
  color: var(--danger);
}

/* 多按钮时用 gap 控制间距，去掉 Element Plus 的相邻按钮左边距（避免双份间距） */
.item__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* ---- 表单 ---- */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.tip {
  margin-top: 12px;
}

/* 空态文案（#empty 插槽内） */
.state__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-900);
}

.state__text {
  max-width: 420px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-500);
  margin-bottom: 4px;
}

/* 删除确认弹窗的列表：两条后果 */
:deep(.confirm-list) {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-700);
}
</style>
