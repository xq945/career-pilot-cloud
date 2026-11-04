package com.careerpilot.service.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.ai.ResumeOptimizationAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.service.JobService;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.MatchScoringResult;
import com.careerpilot.service.MatchScoringService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ai.ResumeOptimizationAiService;

/**
 * 「创建优化」任务的执行体（M16）：匹配（1 次模型调用）→ 优化生成（1 次模型调用）→ 条件落终态。
 *
 * <p>**同步路径与异步消费者共用本类**（消费者线程 / 同步降级的请求线程），
 * 状态机的推进全部委托 {@link OptimizationTaskStateService}：
 * 调用方负责"领取为 PROCESSING"，本类负责阶段心跳与终态写入。
 *
 * <p>执行期的防御性校验：任务从入队到执行存在时间差，简历/岗位分析可能已失效——
 * 校验失败即抛业务异常（由消费者落 FAILED、文案面向用户）。
 */
@Component
public class OptimizationTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(OptimizationTaskRunner.class);

    private final ResumeOptimizationMapper optimizationMapper;

    private final ResumeMapper resumeMapper;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchService matchService;

    private final MatchScoringService matchScoringService;

    private final ResumeOptimizationAiService resumeOptimizationAiService;

    private final OptimizationTaskStateService stateService;

    public OptimizationTaskRunner(ResumeOptimizationMapper optimizationMapper, ResumeMapper resumeMapper,
                                  JobService jobService, ResumeAnalysisService resumeAnalysisService,
                                  JobAnalysisService jobAnalysisService, MatchService matchService,
                                  MatchScoringService matchScoringService,
                                  ResumeOptimizationAiService resumeOptimizationAiService,
                                  OptimizationTaskStateService stateService) {
        this.optimizationMapper = optimizationMapper;
        this.resumeMapper = resumeMapper;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
        this.matchService = matchService;
        this.matchScoringService = matchScoringService;
        this.resumeOptimizationAiService = resumeOptimizationAiService;
        this.stateService = stateService;
    }

    /**
     * 执行任务（调用方保证：状态已领取为 PROCESSING、attemptId 为本次代次）。
     * 任何异常向上抛出——由调用方决定落 FAILED（消费者）或继续上抛（同步降级路径）。
     */
    public void run(Long optimizationId, String attemptId) {
        ResumeOptimization record = optimizationMapper.selectById(optimizationId);
        if (record == null) {
            throw BusinessException.notFound("优化记录不存在");
        }
        Resume resume = resumeMapper.selectById(record.getResumeId());
        Job job = jobService.getJobById(record.getJobId());
        if (resume == null || job == null) {
            throw BusinessException.badRequest("简历或岗位已不存在，请重新创建优化");
        }
        ResumeAnalysis resumeAnalysis = resumeAnalysisService.findLatestSuccess(resume.getId());
        if (resumeAnalysis == null) {
            throw BusinessException.badRequest("简历分析已失效，请重新创建优化");
        }
        JobAnalysis jobAnalysis = jobAnalysisService.findLatestSuccess(job.getId());
        if (jobAnalysis == null || JobAnalysisService.isStale(job.getDescription(), jobAnalysis)) {
            throw BusinessException.badRequest("岗位分析已失效或已过期，请重新创建优化");
        }

        // ① 匹配：复用 M6 全链路（规则评分 + 模型解释 + 落 match_record），结果作为"优化前评估"
        // 心跳失败 = 代次已失效（任务已被恢复调度重置/被新执行者接管），立即停止，
        // 不再发起昂贵的模型调用（阶段 1 · B3）。
        if (!stateService.updateStage(optimizationId, attemptId, ResumeOptimization.STAGE_MATCHING)) {
            log.warn("阶段推进被拒，停止执行（代次已失效）：optimizationId={} attemptId={}",
                    optimizationId, attemptId);
            return;
        }
        MatchDetail match = matchService.create(resume, job, resumeAnalysis, jobAnalysis);

        // ② 优化生成：证据账本由纯函数评分服务重算（与 match_record 落库分数同源同值）
        if (!stateService.updateStage(optimizationId, attemptId, ResumeOptimization.STAGE_GENERATING)) {
            log.warn("阶段推进被拒，停止执行（代次已失效）：optimizationId={} attemptId={}",
                    optimizationId, attemptId);
            return;
        }
        MatchScoringResult scoring = matchScoringService.score(jobAnalysis, resumeAnalysis);
        AiCallResult<ResumeOptimizationAiOutput> generated =
                resumeOptimizationAiService.generate(resume, jobAnalysis, resumeAnalysis, scoring);

        // ③ 条件落终态（fencing：仅本代次可写；代次失效返回 false 并记 WARN）
        boolean completed = stateService.complete(optimizationId, attemptId, match.getId(),
                generated.value(), generated.modelName(), generated.providerKey());
        if (!completed) {
            log.warn("任务产物未落库（代次已失效）：optimizationId={}", optimizationId);
        }
    }

}
