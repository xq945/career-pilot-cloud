package com.careerpilot.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AgentToolPayloads;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.entity.Job;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;

/**
 * 岗位类 Agent 工具（《Agent设计》§4.3）。
 *
 * <p>与分析规则的复用关系同 {@link ResumeAgentTools}；岗位描述被改过（分析已过期）时
 * {@code JobAnalysisService.analyze} 同样重新分析并生成新版本（过期判定由 isStale 承担）。
 */
@Component
public class JobAgentTools {

    private final JobService jobService;

    private final JobAnalysisService jobAnalysisService;

    public JobAgentTools(JobService jobService, JobAnalysisService jobAnalysisService) {
        this.jobService = jobService;
        this.jobAnalysisService = jobAnalysisService;
    }

    @Tool(description = "分析当前登录用户的一个目标岗位，返回职责、必备技能、加分项与要求。"
            + "当用户要求分析岗位、了解岗位要求或准备面试时调用本工具，不要凭空编造岗位内容。")
    public String analyzeJob(
            @ToolParam(description = "岗位 ID，必须是当前用户拥有的岗位") Long jobId) {
        Long userId = CurrentUser.requireId();
        Job job = jobService.getOwnedJob(userId, jobId);

        JobAnalysisDetail detail = jobAnalysisService.analyze(job);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getId());
        data.put("jobName", job.getJobName());
        data.put("companyName", job.getCompanyName());
        data.put("versionNo", detail.getVersionNo());
        data.put("summary", ToolViews.text(detail.getSummary()));
        data.put("responsibilities", ToolViews.texts(detail.getResponsibilities()));
        data.put("requiredSkills", ToolViews.texts(detail.getRequiredSkills()));
        data.put("preferredSkills", ToolViews.texts(detail.getPreferredSkills()));
        data.put("technologyStack", ToolViews.texts(detail.getTechnologyStack()));
        data.put("educationRequirement", ToolViews.text(detail.getEducationRequirement()));
        data.put("experienceRequirement", ToolViews.text(detail.getExperienceRequirement()));

        String summary = "岗位《" + job.getJobName() + "》分析完成（版本 v" + detail.getVersionNo()
                + "）：必备技能 " + size(detail.getRequiredSkills()) + " 项、加分技能 "
                + size(detail.getPreferredSkills()) + " 项";
        return AgentToolPayloads.success(summary, data);
    }

    private static int size(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

}
