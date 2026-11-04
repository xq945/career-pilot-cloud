package com.careerpilot.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AgentToolPayloads;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.MatchService;

/**
 * 匹配类 Agent 工具（《Agent设计》§4.4）。
 *
 * <p>与 REST {@code POST /api/matches} 的**唯一差异**（《系统架构设计》§6.2：
 * 有意设计、须防实现分叉）：缺分析或岗位分析过期时，REST 返回 400 要求用户先去分析，
 * **匹配服务的 Agent 入口自动代为补齐**再匹配（阶段 7 起该逻辑下沉到
 * {@link MatchService#createForAgent(Long, Long, Long)}，工具只负责一次远程调用）。
 * 其余规则（归属校验、评分权重、失败处理）由匹配服务统一保证。
 *
 * <p>匹配总分**只来自规则计算**（MatchScoringService），模型只负责解释（AG-08）。
 */
@Component
public class MatchAgentTools {

    private final MatchService matchService;

    public MatchAgentTools(MatchService matchService) {
        this.matchService = matchService;
    }

    @Tool(description = "将当前登录用户的一份简历与一个岗位做匹配评分，返回匹配分数、匹配项与差距。"
            + "当用户要求比较简历与岗位、询问匹配度或差距时调用本工具；"
            + "简历或岗位尚未分析时本工具会自动先完成分析，无需用户提前操作。")
    public String matchResumeToJob(
            @ToolParam(description = "简历 ID，必须是当前用户拥有的简历") Long resumeId,
            @ToolParam(description = "岗位 ID，必须是当前用户拥有的岗位") Long jobId) {
        Long userId = CurrentUser.requireId();
        // 一次远程调用：归属校验、缺分析/过期补齐、规则评分与落库全部在 career-match 内完成
        MatchDetail detail = matchService.createForAgent(userId, resumeId, jobId);

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
