/**
 * M21 岗位求职进度用例（**零模型调用**，见《验证记录》§24）。
 *
 * 覆盖《前端原型设计》§5.5（M21）：
 * - 新建岗位的进度默认「待投递」，列表行内下拉可读；
 * - 行内切到「已投递」→ 成功提示 + 行内回显 + 接口回读一致；
 * - 刷新页面后状态持久化（不是仅前端内存）；
 * - 编辑弹窗保存（全量更新）**不重置**进度。
 *
 * 前置：后端 8080（**`APP_CAPTCHA_ENABLED=false`**，否则登录页多一行验证码、输入框索引错位）
 *      + 前端 dev server 5173；job 表已按 V1.13 增量加好 progress_status 列。
 * 默认浏览器端口 9222（与 job-flow / job-apply-flow 相同——都在岗位页，不并行跑）。
 *
 * 用法：node tools/e2e/job-status-flow.mjs
 */
import { closeBrowser, connect, createChecker, launchBrowser, sleep } from './cdp.mjs'

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8080/api'
const PORT = 9222
const STAMP = Date.now()
const USER = `e2e_status_${STAMP}`
const PASS = 'Test1234'

const JOB_NAME = 'Java 后端开发实习生'
const DESC = '岗位职责：\n1. 参与核心业务系统的后端开发。\n\n任职要求：\n1. 熟悉 Java 与 Spring Boot。'

const { check, summary } = createChecker()

const seed = await fetch(`${API}/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, email: `${USER}@example.com`, password: PASS }),
}).then((r) => r.json()).catch((error) => ({ message: error.message }))
if (seed.code !== 0) {
  console.error(`❌ 无法创建测试账号：${seed.message}`)
  process.exit(1)
}

const login = await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ account: USER, password: PASS }),
}).then((r) => r.json())
const TOKEN = login.data?.token

// 岗位直接用接口造（本用例只验证状态交互，不重复覆盖表单索引）
const created = await fetch(`${API}/jobs`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` },
  body: JSON.stringify({ companyName: '示例科技', jobName: JOB_NAME, description: DESC }),
}).then((r) => r.json())
const JOB_ID = created.data?.id
if (!JOB_ID) {
  console.error(`❌ 无法创建测试岗位：${created.message}`)
  process.exit(1)
}

const browser = await launchBrowser({ port: PORT })
const page = await connect({ port: PORT })
await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

const shot = async (name) => {
  await sleep(400)
  await page.screenshot(`./tmp/shots-job-status/${name}.png`)
}

/**
 * 行内下拉当前显示的文本。
 *
 * 不能用 `.el-select__selected-item` 取：Element Plus 2.14 会渲染两个同 class 的节点——
 * 第一个是隐藏的搜索输入（`.is-hidden`，textContent 为空），第二个才是选中值。
 * 直接读 `.el-select` 的 textContent 最稳（选项列表 teleport 在 body 下，不会混进来）。
 */
const displayedStatus = () => page.evaluate(`(() => {
  const root = document.querySelector('.item .el-select')
  return (root?.textContent || '').trim()
})()`)

const openDropdown = () => page.evaluate(`(() => {
  const wrapper = document.querySelector('.item .el-select__wrapper') || document.querySelector('.item .el-select')
  if (!wrapper) throw new Error('找不到行内状态下拉')
  wrapper.click()
  return true
})()`)

const chooseOption = (label) => page.evaluate(`(() => {
  const option = [...document.querySelectorAll('.el-select-dropdown__item')]
    .find((el) => el.textContent.trim() === '${label}' && el.offsetParent !== null)
  if (!option) throw new Error('找不到下拉项：${label}')
  option.click()
  return true
})()`)

const apiProgress = () => fetch(`${API}/jobs/${JOB_ID}`, {
  headers: { Authorization: `Bearer ${TOKEN}` },
}).then((r) => r.json()).then((json) => json.data?.progressStatus)

try {
  // ------------------------------------------------------------ 登录进入岗位页

  await page.goto(`${BASE}/jobs`)
  await page.evaluate('localStorage.clear()')
  await page.goto(`${BASE}/jobs`)
  await page.waitFor(`location.pathname === '/login'`, { label: '守卫重定向', timeout: 20000 })
  await page.fill(0, USER)
  await page.fill(1, PASS)
  await page.click('.auth__submit')
  await page.waitFor(`location.pathname === '/jobs'`, { label: '回跳岗位页', timeout: 20000 })
  await page.waitFor(`document.querySelectorAll('.item').length === 1`, { label: '列表出现岗位', timeout: 20000 })

  // ------------------------------------------------------------ ① 默认状态

  check('① 列表行内状态默认显示「待投递」', (await displayedStatus()).includes('待投递'), await displayedStatus())
  check('   接口回读 progressStatus=PENDING', (await apiProgress()) === 'PENDING', await apiProgress())
  await shot('01-default-pending')

  // ------------------------------------------------------------ ② 行内切换到「已投递」

  await openDropdown()
  await page.waitFor(`[...document.querySelectorAll('.el-select-dropdown__item')]
    .some((el) => el.textContent.trim() === '已投递' && el.offsetParent !== null)`,
  { label: '下拉选项出现', timeout: 10000 })
  await chooseOption('已投递')
  await page.waitFor(`[...document.querySelectorAll('.el-message')]
    .some((e) => e.textContent.includes('进度已更新') && e.textContent.includes('已投递'))`,
  { label: '状态更新成功提示', timeout: 20000 })
  await page.waitFor(`(document.querySelector('.item .el-select')?.textContent || '').includes('已投递')`,
    { label: '行内回显已投递', timeout: 10000 })
  check('② 行内切到「已投递」：提示与回显一致',
    (await displayedStatus()).includes('已投递'), await displayedStatus())
  check('   接口回读 progressStatus=APPLIED', (await apiProgress()) === 'APPLIED', await apiProgress())
  await shot('02-applied')

  // ------------------------------------------------------------ ③ 刷新后持久化

  await page.reload()
  await page.waitFor(`document.querySelectorAll('.item').length === 1`, { label: '刷新后列表渲染', timeout: 20000 })
  check('③ 刷新页面后仍显示「已投递」（已落库，非前端内存）',
    (await displayedStatus()).includes('已投递'), await displayedStatus())

  // ------------------------------------------------------------ ④ 编辑保存不重置进度

  await page.evaluate(`[...document.querySelectorAll('.item .el-button')]
    .find((el) => el.textContent.trim() === '编辑').click()`)
  await page.waitFor(`(() => {
    const input = document.querySelectorAll('.el-dialog .el-input__inner')[1]
    return input?.value === '${JOB_NAME}'
  })()`, { label: '编辑弹窗回填', timeout: 15000 })
  await page.evaluate(`[...document.querySelectorAll('.el-dialog__footer .el-button')]
    .find((el) => el.textContent.trim() === '保存').click()`)
  await page.waitFor(`[...document.querySelectorAll('.el-message')]
    .some((e) => e.textContent.includes('岗位已更新'))`,
  { label: '编辑保存成功提示', timeout: 20000 })
  await page.waitFor(`document.querySelectorAll('.item').length === 1`, { label: '列表刷新', timeout: 20000 })
  check('④ 编辑弹窗保存后进度仍为「已投递」（全量更新不重置状态）',
    (await displayedStatus()).includes('已投递') && (await apiProgress()) === 'APPLIED',
    `${await displayedStatus()} / ${await apiProgress()}`)
  await shot('03-after-edit')

  // ------------------------------------------------------------ ⑤ 收尾：删除岗位

  await page.evaluate(`[...document.querySelectorAll('.item .el-button')]
    .find((el) => el.textContent.trim() === '删除').click()`)
  await page.waitFor(`!!document.querySelector('.el-message-box')`, { label: '删除确认框', timeout: 10000 })
  await page.evaluate(`document.querySelector('.el-message-box__btns .el-button--primary').click()`)
  await page.waitFor(`document.querySelectorAll('.item').length === 0`, { label: '列表清空', timeout: 20000 })
  check('⑤ 收尾：删除岗位成功（列表回到空态）', true)
}
finally {
  page.close()
  await closeBrowser(PORT, browser)
}

const passed = summary()
console.log(`\n测试数据清理：DELETE FROM sys_user WHERE username = '${USER}';`)
process.exit(passed ? 0 : 1)
