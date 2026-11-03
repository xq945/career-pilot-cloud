import request from './request'

/**
 * 岗位接口（《API接口设计》5.1–5.8）。
 *
 * 所有接口的归属校验在后端完成（身份取自 Token），前端不传任何用户标识；
 * 他人岗位 ID 一律返回 404（不区分"不存在"与"无权访问"）。
 */

/** 岗位列表（5.2）：仅本人有效岗位；每条带 analyzed/stale/updatedAt，**不含 description** */
export function listJobs({ page = 1, size = 10 } = {}) {
  return request.get('/jobs', { params: { page, size } })
}

/** 岗位详情（5.3）：含完整描述（编辑弹窗回填用） */
export function getJob(id) {
  return request.get(`/jobs/${id}`)
}

/** 创建岗位（5.1）：companyName 可选 ≤100、jobName 必填 ≤100、description 必填 ≤10000 */
export function createJob(payload) {
  return request.post('/jobs', payload)
}

/** 更新岗位（5.4）：**全量更新**——jobName/description 必填，companyName 省略或 null 表示清空 */
export function updateJob(id, payload) {
  return request.put(`/jobs/${id}`, payload)
}

/**
 * 求职进度切换（5.8，M21）：只改状态，不动描述与其他字段；返回最新详情。
 *
 * 与全量更新（5.4）分开：编辑岗位不会重置进度，列表行内切换也无需整份回传。
 */
export function updateJobStatus(id, status) {
  return request.put(`/jobs/${id}/status`, { status })
}

/** 删除岗位（5.5）：逻辑删除，关联分析记录保留；重复删除幂等成功 */
export function removeJob(id) {
  return request.delete(`/jobs/${id}`)
}

/**
 * 发起岗位分析（5.6）：无请求体；同步调用通常 10–30 秒。
 *
 * AI 接口超时统一设 120 秒（《API接口设计》10.3：与后端总超时对齐）；
 * 每次调用都会重新分析并生成新版本（versionNo 递增，2026-10-13 起不再复用）。
 */
export function analyzeJob(id) {
  return request.post(`/jobs/${id}/analyze`, null, { timeout: 120000 })
}

/**
 * 获取最新一版岗位分析（5.7）。
 *
 * 无分析结果时返回 HTTP 200 + `{ analyzed: false, stale: false, analysis: null }`（不是 404），
 * 调用方据 `analyzed` 区分三态，不要把"未分析"当错误处理。
 *
 * `stale = true` 表示分析基于**旧版岗位描述**（描述在分析后被修改过）：
 * 旧结果仍可展示（降对比度），但不应再用于生成匹配——页面据此展示警告条与重新分析入口。
 */
export function getJobAnalysis(id) {
  return request.get(`/jobs/${id}/analysis`)
}
