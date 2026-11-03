/**
 * M21 岗位求职进度接口层验收（《开发顺序》M21 的「接口层验收」环节）。
 *
 * 前置：后端已在 8080 运行（**必须是加载了 M21 的新实例**）、MySQL 可用
 *      （job 表已按 V1.13 增量加好 progress_status 列：tmp/m21-v113-migration.sql）。
 * 成本：**0 次模型调用**（本功能不涉及 AI 链路）。
 *
 * 覆盖（《API接口设计》§5.8、《数据库设计》§5.18）：
 * - 创建岗位 → 进度默认 PENDING（创建响应 / 详情 / 列表三处一致）；
 * - 八个枚举值均可写入，任意切换（含直接到终态、回退）；
 * - 非法值 / 空值 / 小写 → 40001（Bean Validation），且**状态不变**；
 * - 切换只改 progressStatus 与 updatedAt，不动 description（stale 判定不受影响）；
 * - 全量更新（编辑岗位）**不重置**进度；
 * - 越权 / 已删除 → 404。
 *
 * 运行：node tools/verify/m21-job-status-api.mjs
 * 退出码：0 = 全部通过；1 = 存在失败。
 */
const BASE = process.env.API_BASE ?? 'http://localhost:8080/api'

const PASSWORD = 'verify1234'

let passed = 0
const failures = []

function check(name, condition, detail = '') {
  if (condition) {
    passed += 1
    console.log(`  [OK] ${name}`)
  } else {
    failures.push(detail ? `${name} — ${detail}` : name)
    console.log(`  [FAIL] ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

async function api(method, path, { token, body } = {}) {
  const headers = {}
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  let payload
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(BASE + path, { method, headers, body: payload, signal: AbortSignal.timeout(30000) })
  const json = await res.json().catch(() => null)
  return { status: res.status, body: json }
}

const SUFFIX = Date.now().toString(36)

async function registerAndLogin(prefix) {
  const username = `${prefix}_${SUFFIX}`
  await api('POST', '/auth/register', {
    body: { username, email: `${username}@example.com`, password: PASSWORD },
  })
  const login = await api('POST', '/auth/login', { body: { account: username, password: PASSWORD } })
  return { username, token: login.body?.data?.token }
}

const DESC = '岗位职责：\n1. 参与核心业务系统的后端开发。\n\n任职要求：\n1. 熟悉 Java 与 Spring Boot。'
const ALL_STATUSES = ['PENDING', 'APPLIED', 'WRITTEN', 'INTERVIEW_1', 'INTERVIEW_2', 'HR', 'OFFER', 'REJECTED']

function jobBody(overrides = {}) {
  return { companyName: '示例科技', jobName: 'Java 后端开发实习生', description: DESC, ...overrides }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

console.log(`M21 岗位求职进度接口层验收：${BASE}\n`)

const { username, token } = await registerAndLogin('verify_m21')
check('前置：测试账号注册并登录成功', !!token, `username=${username}`)

// ---------------------------------------------------------------- ① 创建默认 PENDING

const created = await api('POST', '/jobs', { token, body: jobBody() })
const jobId = created.body?.data?.id
check('① 创建岗位 → 进度默认 PENDING（创建响应）',
  created.status === 200 && created.body?.data?.progressStatus === 'PENDING',
  `status=${created.status} progressStatus=${JSON.stringify(created.body?.data?.progressStatus)}`)

const detail0 = await api('GET', `/jobs/${jobId}`, { token })
check('   详情回读 progressStatus=PENDING', detail0.body?.data?.progressStatus === 'PENDING',
  `progressStatus=${JSON.stringify(detail0.body?.data?.progressStatus)}`)

const list0 = await api('GET', '/jobs?page=1&size=10', { token })
const row0 = list0.body?.data?.records?.find((r) => r.id === jobId)
check('   列表回读 progressStatus=PENDING（行内下拉的渲染依据）', row0?.progressStatus === 'PENDING',
  `progressStatus=${JSON.stringify(row0?.progressStatus)}`)

// ---------------------------------------------------------------- ② 八个状态全部可写入

for (const status of ALL_STATUSES) {
  const res = await api('PUT', `/jobs/${jobId}/status`, { token, body: { status } })
  check(`② 切换到 ${status} 成功并回显`,
    res.status === 200 && res.body?.data?.progressStatus === status,
    `status=${res.status} progressStatus=${JSON.stringify(res.body?.data?.progressStatus)}`)
}

// 回退（不设状态机：OFFER → APPLIED 允许）
const rollback = await api('PUT', `/jobs/${jobId}/status`, { token, body: { status: 'APPLIED' } })
check('   任意回退允许（OFFER → APPLIED，不设状态机）',
  rollback.status === 200 && rollback.body?.data?.progressStatus === 'APPLIED',
  `status=${rollback.status} progressStatus=${JSON.stringify(rollback.body?.data?.progressStatus)}`)

// ---------------------------------------------------------------- ③ updatedAt 刷新、描述不动

await sleep(1100) // DATETIME(0)：等过秒边界，才能真正观察到刷新
const before = await api('GET', `/jobs/${jobId}`, { token })
const touched = await api('PUT', `/jobs/${jobId}/status`, { token, body: { status: 'INTERVIEW_1' } })
check('③ 切换后 updatedAt 刷新（接口响应）',
  touched.body?.data?.updatedAt && touched.body?.data?.updatedAt !== before.body?.data?.updatedAt,
  `before=${before.body?.data?.updatedAt} after=${touched.body?.data?.updatedAt}`)
check('   切换不修改 description / stale 判定（描述未变仍为 false）',
  touched.body?.data?.description === DESC && touched.body?.data?.stale === false,
  `stale=${touched.body?.data?.stale}`)

// ---------------------------------------------------------------- ④ 非法输入 → 40001 且状态不变

const invalid = [
  ['未知枚举', { status: 'WISHFUL_THINKING' }],
  ['小写', { status: 'offer' }],
  ['空串', { status: '' }],
  ['缺省字段', {}],
]

for (const [label, body] of invalid) {
  const res = await api('PUT', `/jobs/${jobId}/status`, { token, body })
  check(`④ 非法输入（${label}）→ 40001`,
    res.status === 400 && res.body?.code === 40001,
    `status=${res.status} code=${res.body?.code} message=${res.body?.message}`)
}

const afterInvalid = await api('GET', `/jobs/${jobId}`, { token })
check('   非法请求后状态保持 INTERVIEW_1（未被篡改）',
  afterInvalid.body?.data?.progressStatus === 'INTERVIEW_1',
  `progressStatus=${JSON.stringify(afterInvalid.body?.data?.progressStatus)}`)

// ---------------------------------------------------------------- ⑤ 全量更新不重置进度

const updated = await api('PUT', `/jobs/${jobId}`, {
  token,
  body: jobBody({ jobName: 'Java 后端开发实习生（改）' }),
})
check('⑤ 编辑岗位（全量更新）不重置进度',
  updated.body?.data?.progressStatus === 'INTERVIEW_1'
    && updated.body?.data?.jobName === 'Java 后端开发实习生（改）',
  `progressStatus=${JSON.stringify(updated.body?.data?.progressStatus)}`)

// ---------------------------------------------------------------- ⑥ 越权与已删除

const other = await registerAndLogin('verify_m21b')
const foreign = await api('PUT', `/jobs/${jobId}/status`, { token: other.token, body: { status: 'OFFER' } })
check('⑥ 他人岗位切换状态 → 404（不区分"不存在"与"他人"）',
  foreign.status === 404 && foreign.body?.code === 40400,
  `status=${foreign.status} code=${foreign.body?.code}`)

await api('DELETE', `/jobs/${jobId}`, { token })
const deleted = await api('PUT', `/jobs/${jobId}/status`, { token, body: { status: 'OFFER' } })
check('   已删除岗位切换状态 → 404', deleted.status === 404 && deleted.body?.code === 40400,
  `status=${deleted.status} code=${deleted.body?.code}`)

// ---------------------------------------------------------------- 汇总

console.log(`\n结果：${passed} 项通过，${failures.length} 项失败`)
if (failures.length > 0) {
  console.log('失败清单：')
  for (const f of failures) {
    console.log(`  - ${f}`)
  }
}

console.log(`\n测试数据清理（后端无删除用户接口，需手工执行）：`)
console.log(`  DELETE FROM job WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE 'verify_m21%');`)
console.log(`  DELETE FROM sys_user WHERE username LIKE 'verify_m21%';`)

process.exit(failures.length === 0 ? 0 : 1)
