import test from 'node:test'
import assert from 'node:assert/strict'

import { resolveSubmitFeedback, resolveSubmitSuccess } from '../src/utils/optimizationSubmit.js'

/**
 * O-16 的前端提示判定测试（node --test，无额外依赖）。
 * 重点验证：只在能确认的情况下下结论；同步降级失败带回 taskId 时不得说“未产生记录”。
 */

test('手动输入岗位：创建岗位失败时不涉及优化任务', () => {
  const feedback = resolveSubmitFeedback({
    error: { message: '岗位名称不能为空', httpStatus: 400 },
    failedStep: 'creating-job',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /^创建岗位失败/)
})

test('手动输入岗位：岗位已创建但分析失败时保留重试提示', () => {
  const feedback = resolveSubmitFeedback({
    error: { message: '大模型服务暂时不可用', httpStatus: 502 },
    failedStep: 'analyzing-job',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /岗位已创建/)
  assert.match(feedback.text, /不会重复创建岗位/)
})

test('同步降级失败：后端带回 optimizationId 时提示任务已创建且可重试', () => {
  const feedback = resolveSubmitFeedback({
    error: {
      code: 50201,
      message: '大模型输出无法解析，请稍后重试',
      httpStatus: 502,
      data: { optimizationId: 301 },
    },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, 301)
  assert.match(feedback.text, /任务已创建/)
  assert.match(feedback.text, /执行失败/)
  assert.match(feedback.text, /重试/)
  // 核心回归：不能再出现“未产生记录”的断言
  assert.doesNotMatch(feedback.text, /未产生/)
})

test('执行期复核失败（400）但带回 taskId：仍按已创建处理', () => {
  const feedback = resolveSubmitFeedback({
    error: {
      code: 40000,
      message: '岗位分析已失效或已过期，请重新创建优化',
      httpStatus: 400,
      data: { optimizationId: 301 },
    },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, 301)
  assert.match(feedback.text, /任务已创建/)
  assert.doesNotMatch(feedback.text, /未创建任务/)
})

test('创建前校验失败（400）可以确认未创建任务', () => {
  const feedback = resolveSubmitFeedback({
    error: { code: 40000, message: '请先完成简历分析', httpStatus: 400, data: null },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /^未创建任务/)
})

test('归属/资源不存在（404）可以确认未创建任务', () => {
  const feedback = resolveSubmitFeedback({
    error: { code: 40400, message: '简历不存在', httpStatus: 404, data: null },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /^未创建任务/)
})

test('502 但未带回任务 ID：不能声称一定没有记录', () => {
  const feedback = resolveSubmitFeedback({
    error: { code: 50200, message: '大模型服务暂时不可用', httpStatus: 502, data: null },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /结果未知/)
  assert.match(feedback.text, /优化列表/)
  assert.doesNotMatch(feedback.text, /未产生/)
})

test('网络超时/无响应：提示到列表确认', () => {
  const feedback = resolveSubmitFeedback({
    error: { code: -1, message: '请求超时，请稍后重试', httpStatus: null, data: null },
    failedStep: 'optimizing',
  })
  assert.equal(feedback.taskId, null)
  assert.match(feedback.text, /结果未知/)
})

test('成功提示：异步 PENDING 与同步 SUCCESS 区分', () => {
  assert.equal(resolveSubmitSuccess('PENDING'), '已提交，优化任务在后台执行')
  assert.equal(resolveSubmitSuccess('PROCESSING'), '已提交，优化任务在后台执行')
  assert.equal(resolveSubmitSuccess('SUCCESS'), '优化已完成')
})
