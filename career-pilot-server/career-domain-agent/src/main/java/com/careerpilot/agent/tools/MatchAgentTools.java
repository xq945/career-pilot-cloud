package com.careerpilot.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AgentToolPayloads;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 匹配类 Agent 工具（《Agent设计》§4.4）。
 *
 * <p>与 REST {@code POST /api/matches} 的**唯一差异**（《系统架构设计》§6.2：
 * 有意设计、须防实现分叉）：缺分析或岗位分析过期时，REST 返回 400 要求用户先去分析，
 * **本工具自动代为补齐**再匹配。其余规则（归属校验、source_hash 复用、评分权重、
 * 失败处理、事务边界）完全共用 {@link MatchService#create(Resume, Job, ResumeAnalysis, JobAnalysis)}，
 * 行为不因入口不同而分叉。
 *
 * <p>匹配总分**只来自规则计算**（MatchScoringService），模型只负责解释（AG-08）。
 */
@Component
public class MatchAgentTools {

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchService matchService;

    public MatchAgentTools(ResumeService resumeService, JobService jobService,
                           ResumeAnalysisService resumeAnalysisService,
                           JobAnalysisService jobAnalysisService, MatchService matchService) {
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
        this.matchService = matchService;
    }

    @Tool(description = "将当前登录用户的一份简历与一个岗位做匹配评分，返回匹配分数、匹配项与差距。"
            + "当用户要求比较简历与岗位、询问匹配度或差距时调用本工具；"
            + "简历或岗位尚未分析时本工具会自动先完成分析，无需用户提前操作。")
    public String matchResumeToJob(
            @ToolParam(description = "简历 ID，必须是当前用户拥有的简历") Long resumeId,
            @ToolParam(description = "岗位 ID，必须是当前用户拥有的岗位") Long jobId) {
        Long userId = CurrentUser.requireId();
        Resume resume = resumeService.getOwnedResume(userId, resumeId);
        Job job = jobService.getOwnedJob(userId, jobId);

        // —— 与 REST 的差异：缺分析时自动补齐（补齐同样受工具次数/总耗时上限约束）——
        ResumeAnalysis resumeAnalysis = resumeAnalysisService.findLatestSuccess(resumeId);
        if (resumeAnalysis == null) {
            resumeAnalysisService.analyze(resume);
            resumeAnalysis = resumeAnalysisService.findLatestSuccess(resumeId);
        }
        JobAnalysis jobAnalysis = jobAnalysisService.findLatestSuccess(jobId);
        // 注意：isStale 对"无分析"恒为 false（无分析谈不上过期），必须显式判空
        if (jobAnalysis == null || JobAnalysisService.isStale(job.getDescription(), jobAnalysis)) {
            // 缺分析或分析已过期（岗位描述被改过）：重算，analyze 内部按 source_hash 决定复用/生成
            jobAnalysisService.analyze(job);
            jobAnalysis = jobAnalysisService.findLatestSuccess(jobId);
        }

        // 评分为确定性规则计算（MatchScoringService），与 REST 共用同一实现
        MatchDetail detail = matchService.create(resume, job, resumeAnalysis, jobAnalysis);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchId", detail.getId());
        data.put("resumeId", detail.getResumeId());
        data.put("resumeName", detail.getResumeName());
        data.put("jobId", detail.getJobId());
        data.put("jobName", detail.getJobName());
        data.put("companyName", detail.getCompanyName());
        data.put("matchScore", detail.getMatchScore());
        data.put("dimensionScores", detail.getDimensionScores());
        data.put("matchedItems", ToolViews.matchItems(detail.getMatchedItems()));
        data.put("partialItems", ToolViews.matchItems(detail.getPartialItems()));
        data.put("missingItems", ToolViews.matchItems(detail.getMissingItems()));
        data.put("summary", ToolViews.text(detail.getSummary()));
        data.put("suggestions", ToolViews.texts(detail.getSuggestions()));

        String summary = "简历《" + detail.getResumeName() + "》与岗位《" + detail.getJobName()
                + "》匹配完成：得分 " + detail.getMatchScore() + "（规则计算，非模型估值）";
        return AgentToolPayloads.success(summary, data);
    }

}
