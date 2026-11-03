/**
 * 创建简历优化后的提示判定（O-16）。
 *
 * 后端真实行为：
 * - 创建前的校验失败（400/404）发生在 INSERT 之前，确定没有任务记录；
 * - 同步降级执行失败时，任务行已落库并置为 FAILED，错误响应 data 携带 optimizationId；
 * - 异步任务在创建接口已返回 PENDING，后续失败由详情页轮询展示；
 * - 网络超时/无响应时，前端无法确认任务是否已创建。
 *
 * 因此这里只在能确认的情况下下结论，其余一律提示“结果未知，请到列表确认”。
 */

/**
 * @param {object} params
 * @param {{ code?: number, message?: string, httpStatus?: number|null, data?: { optimizationId?: number } }} params.error
 * @param {'creating-job'|'analyzing-job'|'optimizing'|null} params.failedStep
 * @returns {{ text: string, taskId: number|null }}
 */
export function resolveSubmitFeedback({ error, failedStep } = {}) {
  const message = error?.message || '优化失败，请稍后重试'
  const taskId = error?.data?.optimizationId ?? null
  const httpStatus = error?.httpStatus ?? null

  if (failedStep === 'creating-job') {
    return { text: `创建岗位失败：${message}`, taskId: null }
  }
  if (failedStep === 'analyzing-job') {
    return {
      text: `岗位已创建，但分析失败：${message}（可重试，不会重复创建岗位）`,
      taskId: null,
    }
  }
  if (taskId !== null) {
    return {
      text: `任务已创建，但执行失败：${message}。任务记录仍在，可打开任务查看失败原因并重试。`,
      taskId,
    }
  }
  if (httpStatus === 400 || httpStatus === 404) {
    return { text: `未创建任务：${message}`, taskId: null }
  }
  return {
    text: `提交结果未知：${message}。请到优化列表确认任务是否已创建；如已创建，可在任务详情中重试。`,
    taskId: null,
  }
}

/** 创建接口返回 200 时的成功提示（同步降级成功会直接返回 SUCCESS） */
export function resolveSubmitSuccess(status) {
  if (status === 'SUCCESS') {
    return '优化已完成'
  }
  if (status === 'FAILED') {
    return '任务已创建，但执行失败，可在详情页查看原因并重试'
  }
  return '已提交，优化任务在后台执行'
}
