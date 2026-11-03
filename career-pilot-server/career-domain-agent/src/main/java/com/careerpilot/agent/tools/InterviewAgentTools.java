package com.careerpilot.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.careerpilot.ai.AgentToolPayloads;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewQuestionItem;
import com.careerpilot.entity.Job;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.InterviewService;
import com.careerpilot.service.JobService;

/**
 * 面试类 Agent 工具（《Agent设计》§4.5，第二组工具）。
 *
 * <p>把 M7 的出题能力包装成工具：生成题目后**自动创建面试会话**（题目与会话同事务落库，
 * 失败整体回滚、不产生空会话——复用 {@link InterviewService#create} 的既有写路径）。
 *
 * <p>会话标题取「{岗位名}模拟面试」（V1.4/R27，与 REST §7.1 前端默认值一致），
 * 并在工具内截断到 100 字符——工具入参不经 Bean Validation，长度必须自行保证。
 *
 * <p>工具**不接收 sessionId 参数**（§3：由模型向用户索要数据库 ID 既不合理也不安全）。
 */
@Component
public class InterviewAgentTools {

    /** 题型白名单（与 REST 的 @Pattern、InterviewAiService 一致） */
    private static final Set<String> QUESTION_TYPES =
            Set.of("JAVA", "SPRING", "MYSQL", "REDIS", "PROJECT", "MIXED");

    private static final String DEFAULT_TYPE = "MIXED";

    private static final int DEFAULT_COUNT = 5;

    private static final int MIN_COUNT = 1;

    private static final int MAX_COUNT = 20;

    /** 会话标题上限（agent_session.title 为 VARCHAR(100)，R27） */
    private static final int TITLE_LIMIT = 100;

    private static final String TITLE_SUFFIX = "模拟面试";

    private final JobService jobService;

    private final InterviewService interviewService;

    public InterviewAgentTools(JobService jobService, InterviewService interviewService) {
        this.jobService = jobService;
        this.interviewService = interviewService;
    }

    @Tool(description = "针对当前登录用户的一个岗位生成模拟面试题，并自动创建一个模拟面试会话"
            + "（用户可在平台的「AI 面试」页看到并作答）。返回面试会话 ID 与题目列表。"
            + "当用户要求准备面试题、模拟面试或针对岗位练习时调用本工具。")
    public String generateInterviewQuestions(
            @ToolParam(description = "岗位 ID，必须是当前用户拥有的岗位") Long jobId,
            @ToolParam(description = "题型，取值：JAVA、SPRING、MYSQL、REDIS、PROJECT、MIXED；"
                    + "不确定时用 MIXED", required = false) String questionType,
            @ToolParam(description = "题目数量（1-20）；省略时默认 5", required = false) Integer count) {
        Long userId = CurrentUser.requireId();
        Job job = jobService.getOwnedJob(userId, jobId);

        // 入参是模型给的，非法值不抛异常，返回失败信封让模型修正或向用户说明
        String type = questionType == null || questionType.isBlank()
                ? DEFAULT_TYPE : questionType.trim().toUpperCase();
        if (!QUESTION_TYPES.contains(type)) {
            return AgentToolPayloads.failure("INVALID_ARGUMENT",
                    "题型必须是 JAVA、SPRING、MYSQL、REDIS、PROJECT、MIXED 之一，收到：" + questionType);
        }
        int questionCount = count == null ? DEFAULT_COUNT : count;
        if (questionCount < MIN_COUNT || questionCount > MAX_COUNT) {
            return AgentToolPayloads.failure("INVALID_ARGUMENT",
                    "题目数量必须在 " + MIN_COUNT + "-" + MAX_COUNT + " 之间，收到：" + count);
        }

        InterviewCreateRequest request = new InterviewCreateRequest();
        request.setJobId(jobId);
        request.setTitle(titleOf(job));
        request.setQuestionType(type);
        request.setQuestionCount(questionCount);

        // 模型调用（出题，通常 10–40 秒）在事务外；失败 502 由包装器转为失败信封（AG-10）
        InterviewCreateResponse response = interviewService.create(userId, request);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", response.getId());
        data.put("title", response.getTitle());
        data.put("jobName", job.getJobName());
        data.put("questionType", response.getQuestionType());
        data.put("questionCount", response.getQuestionCount());
        data.put("generatedCount", response.getGeneratedCount());
        data.put("questions", questionItems(response.getQuestions()));

        String summary = "已为岗位《" + job.getJobName() + "》生成 " + response.getGeneratedCount()
                + " 道模拟面试题并创建面试会话（会话 ID " + response.getId() + "），"
                + "可在「AI 面试」页查看并作答";
        return AgentToolPayloads.success(summary, data);
    }

    private static List<Map<String, Object>> questionItems(List<InterviewQuestionItem> questions) {
        if (questions == null) {
            return List.of();
        }
        return questions.stream().map(question -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sortOrder", question.getSortOrder());
            item.put("question", ToolViews.text(question.getQuestion()));
            item.put("questionCategory", question.getQuestionCategory() == null
                    ? "" : question.getQuestionCategory());
            return item;
        }).toList();
    }

    /** 标题：{岗位名}模拟面试，截断 100（服务层不触发 Bean Validation，长度自行保证） */
    private static String titleOf(Job job) {
        String name = job.getJobName() == null ? "" : job.getJobName().trim();
        String title = name + TITLE_SUFFIX;
        return title.length() > TITLE_LIMIT ? title.substring(0, TITLE_LIMIT) : title;
    }

}
