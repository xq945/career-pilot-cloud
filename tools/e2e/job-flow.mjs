/**
 * 岗位模块端到端验证（真实浏览器 + 真实模型调用）。
 *
 * 覆盖《开发顺序》M5 的四条验收标准与《前端原型设计》§5.5／§5.6 对应的页面行为：
 * 新建 / 编辑弹窗（含校验拦截）、分析页直达（`?auto=1`）、确认 + AiWaiting 等待态 + 结果渲染、
 * **改描述后 stale 立即为 true（接口层）与分析页警告条**、过期状态下重新分析生成新版本、
 * 描述未变重新分析同样生成新版本（2026-10-13 起不复用）、stale 警告条与降对比度、删除确认与空态；
 * 列表页操作列只剩「投递（有链接时）/ 编辑 / 删除」（M20：分析入口与三态标签已删除）。
 * M20 起「发起分析 / 查看分析」不再有列表按钮可点——脚本经接口层取 id，直接 goto 分析页。
 *
 * 接口层的 stale 三态、50200/50201 与越权矩阵由 curl 验证（见《验证记录》§7.1），
 * 本脚本只补浏览器独有的部分。**本脚本会消耗真实模型 token（三次完整分析）**。
 *
 * 前置条件：后端（8080，真实 API Key）与前端 dev server（5173）都已启动。
 *
 * 用法：
 *   node tools/e2e/job-flow.mjs
 *   node tools/e2e/job-flow.mjs --shots ./tmp/shots-job
 */
import { closeBrowser, connect, createChecker, launchBrowser, sleep } from './cdp.mjs'

// ---------------------------------------------------------------- 参数

const args = process.argv.slice(2)
const argValue = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const BASE = argValue('base', 'http://localhost:5173')
const API = argValue('api', 'http://localhost:8080/api')
const PORT = Number(argValue('port', '9222'))
const SHOTS = argValue('shots', '')

const STAMP = Date.now()
const USER = `e2e_job_${STAMP}`
const PASS = 'Test1234'

/** 真实模型调用的等待上限（通常 10–30 秒，留足重试余量） */
const ANALYSIS_TIMEOUT = 120000

const { check, summary } = createChecker()

const COMPANY = '示例科技'
const JOB_NAME = 'Java 后端开发实习生'
const DESC_V1 = '岗位职责：\n1. 参与核心业务系统的后端开发，负责接口设计与实现；\n2. 配合前端完成联调。\n\n'
  + '任职要求：\n1. 熟悉 Java 与 Spring Boot；\n2. 熟悉 MySQL 索引与优化。'
const DESC_V2 = DESC_V1 + '\n\n加分项：\n1. 了解 Redis 缓存与 Docker 部署。'

// ---------------------------------------------------------------- 准备测试账号

console.log(`前置检查：后端 ${API} / 前端 ${BASE}\n`)

const seed = await fetch(`${API}/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, email: `${USER}@example.com`, password: PASS }),
}).then((r) => r.json()).catch((error) => ({ message: error.message }))

if (seed.code !== 0) {
  console.error(`❌ 无法创建测试账号（后端未启动？）：${seed.message || JSON.stringify(seed)}`)
  process.exit(1)
}
console.log(`已创建测试账号 ${USER}\n`)

// ---------------------------------------------------------------- Node 侧登录（取资源 id 用）

// M20 起列表页不再有「发起分析 / 查看分析」按钮，脚本改为：创建后经接口层取 id，直达分析页
// （`?auto=1` 的自动发起语义在页面路由上，与按钮跳转等价）。
const loginBody = await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ account: USER, password: PASS }),
}).then((r) => r.json())
const TOKEN = loginBody.data?.token
if (!TOKEN) {
  console.error(`❌ 无法登录测试账号：${loginBody.message || JSON.stringify(loginBody)}`)
  process.exit(1)
}

/** 当前用户的岗位 id 列表（列表接口按 created_at 倒序） */
async function listJobIds() {
  const res = await fetch(`${API}/jobs?page=1&size=10`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  }).then((r) => r.json())
  return (res.data?.records ?? []).map((item) => item.id)
}

// ---------------------------------------------------------------- 启动浏览器

const browser = await launchBrowser({ port: PORT })
const page = await connect({ port: PORT })
await page.send('Emulation.setDeviceMetricsOverride', {
  width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
})

/**
 * 截图。**必须先等 el-tag 的进入动画结束**：标签带 `el-zoom-in-center`
 * 过渡（从缩小态放大），动画期间截图会拍到被裁切/半透明的中间态
 * （与断言同理——动画期间新旧标签并存，见 tools/e2e/README.md 的坑清单）。
 */
const shot = async (name) => {
  if (SHOTS) {
    await page
      .waitFor(
        `![...document.querySelectorAll('.el-tag')].some((el) => el.className.includes('enter-active'))`,
        { label: '标签动画结束', timeout: 5000 },
      )
      .catch(() => {})
    await sleep(250)
    await page.screenshot(`${SHOTS}/${name}.png`)
  }
}

/** ElMessageBox 是否可见（动态插入 DOM，关闭即移除；仍用 offsetParent 双保险） */
const MESSAGE_BOX_VISIBLE = `(() => {
  const el = document.querySelector('.el-message-box')
  return !!el && el.offsetParent !== null
})()`

const messageBoxText = () => page.evaluate(
  `(() => {
     const el = document.querySelector('.el-message-box')
     return el ? el.innerText : ''
   })()`)

const clickMessageBoxConfirm = () => page.evaluate(`(() => {
  const btn = document.querySelector('.el-message-box__btns .el-button--primary')
  if (!btn) throw new Error('找不到确认按钮')
  btn.click()
  return true
})()`)

/** 等待岗位列表页渲染完成 */
const waitJobsReady = () => page.waitFor(
  `document.querySelector('.page-head h1')?.textContent?.trim() === '岗位管理'`,
  { label: '岗位页渲染完成', timeout: 20000 })

/** 等待分析页渲染完成（页头出现岗位名或占位标题） */
const waitAnalysisReady = () => page.waitFor(
  `document.querySelector('.page-head h1')?.textContent?.trim() !== ''`,
  { label: '岗位分析页渲染完成', timeout: 20000 })

/** 用原生 setter 填写弹窗内的输入框（textarea 需另指定选择器） */
const fillDialogInput = (index, value, selector = '.el-dialog .el-input__inner') =>
  page.fill(index, value, selector)

/** 点击弹窗底部按钮（按文案） */
const clickDialogButton = (text) => page.evaluate(`(() => {
  const btn = [...document.querySelectorAll('.el-dialog__footer .el-button')]
    .find((el) => el.textContent.trim() === '${text}')
  if (!btn) throw new Error('找不到弹窗按钮：${text}')
  btn.click()
  return true
})()`)

/** 点击页头按钮（按文案，如「新建岗位」） */
const clickPageHeadButton = (text) => page.evaluate(`(() => {
  const btn = [...document.querySelectorAll('.page-head .el-button')]
    .find((el) => el.textContent.includes('${text}'))
  if (!btn) throw new Error('找不到页头按钮：${text}')
  btn.click()
  return true
})()`)

/** 点击列表行内按钮（按文案定位第一处匹配） */
const clickItemButton = (text) => page.evaluate(`(() => {
  const btn = [...document.querySelectorAll('.item .el-button')]
    .find((el) => el.textContent.trim() === '${text}')
  if (!btn) throw new Error('找不到列表按钮：${text}')
  btn.click()
  return true
})()`)

/**
 * 等待列表渲染出可操作的行。
 * 返回列表会触发重新加载（onMounted），期间 StateBlock 显示骨架屏——
 * 点按钮前必须等 `.item` 真正渲染出来（README：路由跳转的 URL 更新早于组件渲染）。
 */
const waitItemButtons = () => page.waitFor(
  `[...document.querySelectorAll('.item .el-button')].some((el) => el.textContent.trim() === '编辑')`,
  { label: '列表项渲染完成', timeout: 20000 })

/** 等待空态标题出现（空态前有一段 loading 骨架屏，不能只等 `.state` 存在） */
const waitEmptyState = () => page.waitFor(
  `document.querySelector('.state__title')?.textContent?.includes('还没有目标岗位') === true`,
  { label: '空态标题渲染', timeout: 20000 })

/** 点击页内按钮（非列表、非弹窗，如警告条里的「重新分析岗位」） */
const clickPageButton = (text) => page.evaluate(`(() => {
  const btn = [...document.querySelectorAll('.el-button')]
    .find((el) => el.textContent.trim() === '${text}' && el.offsetParent !== null)
  if (!btn) throw new Error('找不到按钮：${text}')
  btn.click()
  return true
})()`)

/** 当前 ElMessage 是否包含指定文案（全局轻提示，自动消失） */
const hasMessage = (text) => page.evaluate(
  `[...document.querySelectorAll('.el-message')].some((e) => e.textContent.includes('${text}'))`)

try {
  // ------------------------------------------------------------ ① 登录并进入岗位页（空态）

  // 先访问受保护页触发守卫重定向（登录后带 redirect 回跳 /jobs）
  await page.goto(`${BASE}/jobs`)
  await page.evaluate('localStorage.clear()')
  await page.goto(`${BASE}/jobs`)
  await page.waitFor(`location.pathname === '/login'`, { label: '守卫重定向到登录页', timeout: 20000 })
  await page.fill(0, USER)
  await page.fill(1, PASS)
  await page.click('.auth__submit')
  await page.waitFor(`location.pathname === '/jobs'`, { label: '登录后回跳岗位页', timeout: 20000 })
  await waitJobsReady()
  await waitEmptyState()

  const emptyText = await page.textOf('.state')
  check('① 空态：文案说明用途并有「新建岗位」入口',
    emptyText.includes('还没有目标岗位') && emptyText.includes('简历定向优化'),
    emptyText.replace(/\s+/g, ' ').slice(0, 50))
  await shot('01-jobs-empty')

  // ------------------------------------------------------------ ② 新建岗位（校验拦截 → 正常创建）

  await clickPageHeadButton('新建岗位')
  await page.waitFor(
    `(() => { const el = document.querySelector('.el-dialog'); return !!el && el.offsetParent !== null })()`,
    { label: '新建弹窗打开' })
  await page.waitFor(
    `[...document.querySelectorAll('.el-dialog__footer .el-button')].some((el) => el.textContent.trim() === '保存')`,
    { label: '弹窗渲染完成' })
  check('   新建弹窗标题为「新建岗位」', (await page.textOf('.el-dialog__title')).includes('新建岗位'))
  await shot('02-create-dialog')

  // 空表单提交 → 必填校验拦截，不发请求
  await clickDialogButton('保存')
  await sleep(500)
  const emptyErrors = await page.evaluate(
    `[...document.querySelectorAll('.el-dialog .el-form-item__error')].map((e) => e.textContent.trim())`)
  check('② 空表单提交被拦截（出现必填校验提示，弹窗未关闭）',
    emptyErrors.length >= 1 && await page.evaluate(
      `(() => { const el = document.querySelector('.el-dialog'); return !!el && el.offsetParent !== null })()`),
    emptyErrors.join('/'))

  // 超长描述 → max 校验拦截（脚本赋值可绕过 maxlength 属性，正好覆盖 rules）
  await fillDialogInput(0, 'x'.repeat(10001), '.el-dialog .el-textarea__inner')
  await clickDialogButton('保存')
  await sleep(500)
  const lengthErrors = await page.evaluate(
    `[...document.querySelectorAll('.el-dialog .el-form-item__error')].map((e) => e.textContent.trim())`)
  check('   描述超 10000 字符被拦截（提示含 10000）',
    lengthErrors.some((t) => t.includes('10000')), lengthErrors.join('/'))

  // 正常创建
  await fillDialogInput(0, COMPANY)
  await fillDialogInput(1, JOB_NAME)
  await fillDialogInput(0, DESC_V1, '.el-dialog .el-textarea__inner')
  await clickDialogButton('保存')

  await page.waitFor(`document.querySelectorAll('.item').length === 1`,
    { label: '列表出现新岗位', timeout: 20000 })
  const [jobId] = await listJobIds()
  check('   创建成功，接口层可见该岗位', Number.isInteger(jobId), String(jobId))
  const rowButtons = await page.evaluate(
    `[...document.querySelectorAll('.item .el-button')].map((el) => el.textContent.trim())`)
  check('   操作列只有「编辑 / 删除」（M20：分析入口已删除）',
    rowButtons.includes('编辑') && rowButtons.includes('删除')
      && !rowButtons.some((t) => ['发起分析', '查看分析'].includes(t)),
    rowButtons.join('/'))
  check('   列表项无分析状态标签',
    (await page.evaluate(`document.querySelectorAll('.item .el-tag').length`)) === 0)
  const metaText = await page.textOf('.item__meta')
  check('   列表项显示公司与创建/更新时间',
    metaText.includes(COMPANY) && metaText.includes('创建于') && metaText.includes('更新于'),
    metaText.replace(/\s+/g, ' ').slice(0, 60))
  await shot('03-list-created')

  // ------------------------------------------------------------ ③ 直达分析页并自动发起（确认 + 等待态 + 结果）

  await page.goto(`${BASE}/jobs/${jobId}/analysis?auto=1`)
  await page.waitFor(`/^\\/jobs\\/\\d+\\/analysis$/.test(location.pathname)`,
    { label: '进入分析页', timeout: 15000 })
  check('③ 以 ?auto=1 直达分析页（等价原列表「发起分析」跳转）',
    (await page.currentPath()).includes('auto=1'), await page.currentPath())

  await page.waitFor(MESSAGE_BOX_VISIBLE, { label: '自动弹出确认框', timeout: 15000 })
  const confirmText = await messageBoxText()
  check('   进入后自动弹出确认框且说明耗时',
    confirmText.includes('10–30 秒'),
    confirmText.replace(/\s+/g, ' ').slice(0, 60))

  await clickMessageBoxConfirm()
  await page.waitFor(`!!document.querySelector('.ai-waiting')`, { label: '等待态出现', timeout: 10000 })
  const waitingText = await page.textOf('.ai-waiting')
  check('   确认后出现 AiWaiting 等待态（岗位版文案 + 可离开提示）',
    waitingText.includes('AI 正在阅读岗位要求') && waitingText.includes('10–30 秒'),
    waitingText.replace(/\s+/g, ' ').slice(0, 60))
  const headTagsDuring = await page.evaluate(
    `[...document.querySelectorAll('.page-head .el-tag')].map((e) => e.textContent.trim())`)
  check('   等待期间页头标签为「分析中」', headTagsDuring.includes('分析中'), headTagsDuring.join('/'))
  await shot('04-ai-waiting')

  console.log(`   等待真实模型分析完成（最长 ${ANALYSIS_TIMEOUT / 1000} 秒）…`)
  await page.waitFor(`!document.querySelector('.ai-waiting')`, { label: '等待态消失', timeout: ANALYSIS_TIMEOUT })
  await page.waitFor(`!!document.querySelector('.summary')`, { label: '分析结果渲染', timeout: 20000 })

  const summaryText = await page.textOf('.summary')
  const dutyCount = await page.evaluate(`document.querySelectorAll('.bullets li').length`)
  const requiredCount = await page.evaluate(
    `document.querySelectorAll('.grid-2 .el-card:first-child .el-tag').length`)
  const detailRows = await page.evaluate(`document.querySelectorAll('.detail-row').length`)
  check('④ 结果渲染：概述非空（中文无乱码）', summaryText.trim().length > 10, `${summaryText.trim().length} 字`)
  check('   岗位职责 ≥ 1 条、必备技能 ≥ 1 项、明细 4 行（技术栈/学历/经验/软性）',
    dutyCount >= 1 && requiredCount >= 1 && detailRows === 4,
    `${dutyCount} 职责 / ${requiredCount} 必备技能 / ${detailRows} 明细行`)

  const sideText = await page.textOf('.layout__side')
  check('   岗位信息含公司、版本 v1、模型名与时间',
    sideText.includes(COMPANY) && sideText.includes('v1') && sideText.includes('deepseek'),
    sideText.replace(/\s+/g, ' ').slice(0, 70))
  const headTagsAfter = await page.evaluate(
    `[...document.querySelectorAll('.page-head .el-tag')].map((e) => e.textContent.trim())`)
  check('   页头标签变为「已分析」', headTagsAfter.includes('已分析'), headTagsAfter.join('/'))
  check('   非过期状态：无警告条、结果区不降对比度',
    !(await page.exists('.stale-alert'))
      && Number(await page.evaluate(`getComputedStyle(document.querySelector('.layout')).opacity`)) === 1)
  await shot('05-analysis-result')

  // ------------------------------------------------------------ ④ 改描述 → stale 立即生效（接口层）

  await clickPageButton('返回岗位列表')
  await waitJobsReady()
  await waitItemButtons()
  await clickItemButton('编辑')
  await page.waitFor(
    `(() => { const el = document.querySelector('.el-dialog'); return !!el && el.offsetParent !== null })()`,
    { label: '编辑弹窗打开' })
  await page.waitFor(
    `document.querySelector('.el-dialog .el-input__inner')?.value === '${COMPANY}'`,
    { label: '详情回填完成（列表不含 description，需取详情）', timeout: 15000 })
  check('   编辑弹窗标题为「编辑岗位」（M20：常驻警告条已删除）',
    (await page.textOf('.el-dialog__title')).includes('编辑岗位')
      && !(await page.exists('.el-dialog .el-alert')))

  await fillDialogInput(0, DESC_V2, '.el-dialog .el-textarea__inner')
  await clickDialogButton('保存')
  // 保存是异步的（弹窗先关、列表后重载）：等全局成功提示出现再查接口，否则会读到旧描述的数据
  await page.waitFor(
    `[...document.querySelectorAll('.el-message')].some((e) => e.textContent.includes('岗位已更新'))`,
    { label: '保存成功提示出现', timeout: 20000 })
  // 列表页已无三态标签（M20）：过期判定改由接口层 stale + 下方分析页警告条承接
  const staleJob = await fetch(`${API}/jobs/${jobId}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  }).then((r) => r.json())
  check('⑤ 修改岗位描述后 stale 立即为 true（验收标准 1，接口层）',
    staleJob.data?.stale === true, String(staleJob.data?.stale))
  const metaAfter = await page.textOf('.item__meta')
  check('   列表项显示「更新于」已刷新',
    metaAfter.includes('更新于'), metaAfter.replace(/\s+/g, ' ').slice(0, 60))
  await shot('06-list-updated')

  // ------------------------------------------------------------ ⑤ 详情页警告条与降对比度

  await page.goto(`${BASE}/jobs/${jobId}/analysis`)
  await page.waitFor(`/^\\/jobs\\/\\d+\\/analysis$/.test(location.pathname)`,
    { label: '进入分析页', timeout: 15000 })
  await page.waitFor(`!!document.querySelector('.stale-alert')`, { label: '警告条出现', timeout: 20000 })
  const staleText = await page.textOf('.stale-alert')
  check('⑥ 过期状态：顶部警告条说明原描述更新与当前依据版本',
    staleText.includes('当前分析基于旧版岗位描述') && staleText.includes('v1')
      && staleText.includes('重新分析'),
    staleText.replace(/\s+/g, ' ').slice(0, 80))
  check('   旧结果降对比度展示（opacity < 1）',
    Number(await page.evaluate(`getComputedStyle(document.querySelector('.layout')).opacity`)) < 1,
    `opacity=${await page.evaluate(`getComputedStyle(document.querySelector('.layout')).opacity`)}`)
  check('   页头标签为「分析已过期」',
    (await page.evaluate(`[...document.querySelectorAll('.page-head .el-tag')].map((e) => e.textContent.trim())`))
      .includes('分析已过期'))
  await shot('07-analysis-stale')

  // ------------------------------------------------------------ ⑥ 过期状态下重新分析 → 新版本

  console.log('   等待重新分析（按最新描述生成新版本）…')
  await clickPageButton('重新分析岗位')
  await page.waitFor(MESSAGE_BOX_VISIBLE, { label: '重新分析确认框', timeout: 10000 })
  const reConfirm = await messageBoxText()
  check('   过期状态下的确认框说明将按最新描述生成新版本',
    reConfirm.includes('按最新描述生成新版本'), reConfirm.replace(/\s+/g, ' ').slice(0, 60))
  await clickMessageBoxConfirm()
  await page.waitFor(`!!document.querySelector('.ai-waiting')`, { label: '等待态出现', timeout: 10000 })
  await page.waitFor(`!document.querySelector('.ai-waiting')`, { label: '等待态消失', timeout: ANALYSIS_TIMEOUT })
  await page.waitFor(`!document.querySelector('.stale-alert')`, { label: '警告条消失', timeout: 20000 })

  const sideTextV2 = await page.textOf('.layout__side')
  check('⑦ 过期后重新分析：生成新版本 v2，警告条消失（验收标准 2）',
    sideTextV2.includes('v2') && !(await page.exists('.stale-alert')),
    sideTextV2.replace(/\s+/g, ' ').slice(0, 60))
  check('   页头标签恢复「已分析」',
    (await page.evaluate(`[...document.querySelectorAll('.page-head .el-tag')].map((e) => e.textContent.trim())`))
      .includes('已分析'))
  await shot('08-analysis-refreshed')

  // ------------------------------------------------------------ ⑦ 描述未变重新分析（生成新版本）

  await clickPageButton('重新分析')
  await page.waitFor(MESSAGE_BOX_VISIBLE, { label: '重新分析确认框', timeout: 10000 })
  await clickMessageBoxConfirm()
  await page.waitFor(`!!document.querySelector('.ai-waiting')`, { label: '等待态出现', timeout: 10000 })
  await page.waitFor(`!document.querySelector('.ai-waiting')`, { label: '等待态消失', timeout: ANALYSIS_TIMEOUT })

  const sideTextReuse = await page.textOf('.layout__side')
  // 失败时附上页内失败态文本（AiWaiting 消失也可能是 502，此时版本号不会前进）
  const failText = await page.evaluate(
    `document.querySelector('.fail') ? document.querySelector('.fail').innerText.replace(/\\n/g, ' ') : '（无失败态）'`)
  check('⑧ 描述未变时重新分析：生成新版本 v3（不再复用）',
    sideTextReuse.includes('v3'),
    `side=${sideTextReuse.replace(/\s+/g, ' ').slice(0, 60)} | fail=${failText.slice(0, 100)}`)
  check('   全局提示如实说明「分析完成」', await hasMessage('分析完成'))

  // ------------------------------------------------------------ ⑧ 删除（确认文案 + 空态）

  await clickPageButton('返回岗位列表')
  await waitJobsReady()
  await waitItemButtons()
  await clickItemButton('删除')
  await page.waitFor(MESSAGE_BOX_VISIBLE, { label: '删除确认框', timeout: 10000 })
  const deleteText = await messageBoxText()
  check('⑨ 删除确认弹窗写清两条后果',
    deleteText.includes('无法用于新的匹配与面试') && deleteText.includes('分析与匹配记录会保留'),
    deleteText.replace(/\s+/g, ' ').slice(0, 70))
  await shot('09-delete-confirm')

  await clickMessageBoxConfirm()
  await page.waitFor(`document.querySelectorAll('.item').length === 0`,
    { label: '列表清空', timeout: 20000 })
  await waitEmptyState()
  check('   删除后列表移除并回到空态', (await page.textOf('.state')).includes('还没有目标岗位'))
  await shot('10-list-empty-after-delete')
}
finally {
  page.close()
  await closeBrowser(PORT, browser)
}

// ---------------------------------------------------------------- 汇总

const passed = summary()

const userId = await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ account: USER, password: PASS }),
}).then((r) => r.json()).then((b) => b.data?.user?.id).catch(() => null)

console.log(`\n测试数据清理（后端无删除用户接口，需手工执行）：`)
console.log(`  -- 数据库（先删分析再删岗位与账号）：`)
console.log(`  DELETE FROM job_analysis WHERE job_id IN (SELECT id FROM job WHERE user_id = ${userId ?? '<uid>'});`)
console.log(`  DELETE FROM job WHERE user_id = ${userId ?? '<uid>'};`)
console.log(`  DELETE FROM sys_user WHERE id = ${userId ?? '<uid>'};`)
if (SHOTS) {
  console.log(`截图目录：${SHOTS}`)
}

process.exit(passed ? 0 : 1)
