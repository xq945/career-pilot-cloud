package com.careerpilot.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AgentToolPayloads;
import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.entity.Resume;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 简历类 Agent 工具（《Agent设计》§4.2）。
 *
 * <p>硬约束（§4.1）：**用户身份绝不作为工具参数**——{@code @Tool} 的参数会被解析为
 * 模型可见的 JSON Schema，因此方法只接收资源 ID，用户 ID 一律从认证上下文获取
 * （PoC 已实测工具执行线程可读到 SecurityContext）。
 *
 * <p>本类只做"取数 → 投影"；分析规则（每次重新分析、失败不落库、版本号）全部复用
 * {@link ResumeAnalysisService#analyze(Resume)}——REST 与工具共用同一 Service，行为不分叉。
 */
@Component
public class ResumeAgentTools {

    private final ResumeService resumeService;

    private final ResumeAnalysisService resumeAnalysisService;

    public ResumeAgentTools(ResumeService resumeService, ResumeAnalysisService resumeAnalysisService) {
        this.resumeService = resumeService;
        this.resumeAnalysisService = resumeAnalysisService;
    }

    @Tool(description = "分析当前登录用户的一份简历，返回技能、优势、待改进项与建议。"
            + "当用户要求分析简历、查看简历内容或询问简历相关问题时调用本工具，不要凭空编造简历内容。")
    public String analyzeResume(
            @ToolParam(description = "简历 ID，必须是当前用户拥有的简历") Long resumeId) {
        Long userId = CurrentUser.requireId();
        Resume resume = resumeService.getOwnedResume(userId, resumeId);

        // 复用 REST 同一入口：每次调用都重新分析并生成新版本（2026-10-13 起不做复用判断）
        ResumeAnalysisDetail detail = resumeAnalysisService.analyze(resume);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resumeId", detail.getResumeId());
        data.put("resumeName", resume.getName());
        data.put("versionNo", detail.getVersionNo());
        data.put("summary", ToolViews.text(detail.getSummary()));
        data.put("skills", ToolViews.skills(detail.getSkills()));
        data.put("advantages", ToolViews.texts(detail.getAdvantages()));
        data.put("weaknesses", ToolViews.texts(detail.getWeaknesses()));
        data.put("suggestions", ToolViews.texts(detail.getSuggestions()));

        String summary = "简历《" + resume.getName() + "》分析完成（版本 v" + detail.getVersionNo()
                + "）：技能 " + size(detail.getSkills()) + " 项、优势 " + size(detail.getAdvantages())
                + " 条、待改进 " + size(detail.getWeaknesses()) + " 条";
        return AgentToolPayloads.success(summary, data);
    }

    private static int size(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

}
