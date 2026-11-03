package com.careerpilot.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import com.careerpilot.ai.AiCallResult;
import com.careerpilot.ai.AiCallTemplate;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.dto.ai.ResumeOptimizationAiOutput;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.service.MatchScoringResult;
import com.careerpilot.service.optimization.OptimizationFactCheckException;
import com.careerpilot.service.optimization.ResumeOptimizationFactChecker;

/**
 * 简历优化清洗的护栏（《API接口设计》§6A、《Agent设计》6.4）。
 *
 * 覆盖四条硬要求：优化稿超长截断与**空稿 50201**、changes 元素的结构校验
 * （缺 section/after 丢弃、type 白名单外置 null）、措辞守卫、gapQuestions 去重与上限。
 */
class ResumeOptimizationAiServiceTest {

    private AiCallTemplate template;

    private ResumeOptimizationFactChecker factChecker;

    private ResumeOptimizationAiService service;

    @BeforeEach
    void setUp() {
        template = mock(AiCallTemplate.class);
        factChecker = mock(ResumeOptimizationFactChecker.class);
        // 清洗类测试只关心结构处理；事实校验的规则由 ResumeOptimizationFactCheckerTest 覆盖。
        // 新增的“清洗→真实校验器”交互测试使用 realCheckerService()，不使用这个恒定通过的 mock。
        when(factChecker.checkWithIntegrity(any(), any(), any()))
                .thenReturn(ResumeOptimizationFactChecker.Result.pass());
        // 读真实提示词资源：模板缺失同样让测试失败（与"启动即失败"的约定一致）
        service = new ResumeOptimizationAiService(
                template, factChecker, new ClassPathResource("prompts/resume-optimization.system.txt"));
    }

    @Test
    void generate_rejectsContentOverLimitWithoutTruncation() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("简历正文".repeat(3000)); // 12000 字符
        output.setChanges(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> run(output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("超过长度限制"));
        // 超长在事实校验之前拒绝，不产生任何截断后的结果
        verify(factChecker, never()).checkWithIntegrity(anyString(), anyString(), any());
    }

    @Test
    void generate_acceptsContentAtExactLimit() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("x".repeat(8000));
        output.setChanges(List.of());

        ResumeOptimizationAiOutput result = run(output);

        assertEquals(8000, result.getOptimizedContent().length());
    }

    @Test
    void sanitize_limitsChangesAndQuestions() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        List<ChangeItem> many = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            many.add(change("项目经历", "REWRITE", "理由 " + i, "原文", "改写后 " + i));
        }
        output.setChanges(many);
        output.setGapQuestions(new ArrayList<>(Collections.nCopies(15, "请确认某项信息？")));

        ResumeOptimizationAiOutput cleaned = run(output);

        assertEquals(30, cleaned.getChanges().size());
        assertEquals(1, cleaned.getGapQuestions().size(), "重复问题必须去重");
    }

    @Test
    void generate_factCheckFailure_throws50201WithoutReturningResult() {
        when(factChecker.checkWithIntegrity(any(), any(), any()))
                .thenReturn(ResumeOptimizationFactChecker.Result.fail(List.of(
                        new ResumeOptimizationFactChecker.Issue(
                                ResumeOptimizationFactChecker.Category.BEFORE_NOT_IN_RESUME,
                                ResumeOptimizationFactChecker.Severity.BLOCKING,
                                "changes[0] 的 before 未能在简历原文中找到", 0))));
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setChanges(List.of(change("项目经历", "REWRITE", "理由", "原文", "改写后")));

        BusinessException ex = assertThrows(BusinessException.class, () -> run(output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"));
        assertTrue(ex.getMessage().contains("changes[0]"));
    }

    @Test
    void generate_retriesOnceWithIssues_whenFactCheckFailsThenPasses() {
        ResumeOptimizationFactChecker.Issue issue = new ResumeOptimizationFactChecker.Issue(
                ResumeOptimizationFactChecker.Category.BEFORE_NOT_IN_RESUME,
                ResumeOptimizationFactChecker.Severity.BLOCKING,
                "changes[0] 的 before 未能在简历原文中找到", 0);
        when(factChecker.checkWithIntegrity(any(), any(), any()))
                .thenReturn(ResumeOptimizationFactChecker.Result.fail(List.of(issue)))
                .thenReturn(ResumeOptimizationFactChecker.Result.pass());
        stubGenerated(outputWith("## 技能\n- Java",
                List.of(change("项目经历", "REWRITE", "理由", "原文", "改写后"))));

        ResumeOptimizationAiOutput result = service.generate(
                resume(), new JobAnalysis(), new ResumeAnalysis(), scoring()).value();

        assertNotNull(result, "第二次校验通过时应返回结果");
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).call(eq("resume-optimization"), anyString(),
                prompts.capture(), eq(ResumeOptimizationAiOutput.class));
        assertTrue(prompts.getAllValues().get(1).contains("<校验问题>"),
                "重试消息必须带校验问题块");
        assertTrue(prompts.getAllValues().get(1).contains("changes[0]"),
                "重试消息必须列出具体问题");
    }

    @Test
    void generate_throwsFactCheckExceptionWithIssues_whenRetryAlsoFails() {
        ResumeOptimizationFactChecker.Issue issue = new ResumeOptimizationFactChecker.Issue(
                ResumeOptimizationFactChecker.Category.BEFORE_NOT_IN_RESUME,
                ResumeOptimizationFactChecker.Severity.BLOCKING,
                "优化稿第 1 块出现原文未支持的表述", null);
        when(factChecker.checkWithIntegrity(any(), any(), any()))
                .thenReturn(ResumeOptimizationFactChecker.Result.fail(List.of(issue)));
        stubGenerated(outputWith("## 技能\n- Java", List.of()));

        OptimizationFactCheckException ex = assertThrows(OptimizationFactCheckException.class,
                () -> service.generate(resume(), new JobAnalysis(), new ResumeAnalysis(), scoring()));

        assertEquals(1, ex.getIssues().size(), "异常必须携带完整问题清单");
        assertTrue(ex.failureDetailJson().contains("BEFORE_NOT_IN_RESUME"),
                "问题清单应可序列化为 failure_detail");
        verify(template, times(2)).call(eq("resume-optimization"), anyString(), anyString(),
                eq(ResumeOptimizationAiOutput.class));
    }

    @Test
    void generate_normalizesResumeContentForPromptAndCheck() {
        Resume resume = resumeWith("项目简介：覆盖简历与岗位管理、简历定\n向优化、人岗匹配");
        stubGenerated(outputWith("## 技能\n- Java", List.of()));

        service.generate(resume, new JobAnalysis(), new ResumeAnalysis(), scoring());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(template).call(eq("resume-optimization"), anyString(), prompts.capture(),
                eq(ResumeOptimizationAiOutput.class));
        assertTrue(prompts.getValue().contains("简历定向优化"),
                "PDF 硬折行必须在进入模型前合并");
        verify(factChecker).checkWithIntegrity(
                eq("项目简介：覆盖简历与岗位管理、简历定向优化、人岗匹配"), anyString(), any());
    }

    @Test
    void sanitize_dropsChangesMissingSectionOrAfter() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setChanges(new ArrayList<>(List.of(
                change("项目经历", "REWRITE", "理由", "原文", "改写后"),
                change(null, "REWRITE", "理由", "原文", "改写后"),
                change("技能", "REWRITE", "理由", "原文", null))));

        ResumeOptimizationAiOutput cleaned = run(output);

        assertEquals(1, cleaned.getChanges().size(), "缺 section 或 after 的元素必须丢弃");
        assertEquals("项目经历", cleaned.getChanges().get(0).getSection());
    }

    @Test
    void sanitize_changeTypeOutOfRangeBecomesNull() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setChanges(new ArrayList<>(List.of(
                change("个人总结", "REWRITE_TO_BETTER", "理由", "原文", "改写后"))));

        ResumeOptimizationAiOutput cleaned = run(output);

        assertEquals(1, cleaned.getChanges().size(), "type 越界只置 null，不丢弃整条");
        assertNull(cleaned.getChanges().get(0).getType());
    }

    @Test
    void sanitize_dropsChangesWithForbiddenWording() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setChanges(new ArrayList<>(List.of(
                change("个人总结", "REWRITE", "你并不具备该能力，因此改写", "原文", "改写后"),
                change("技能", "KEYWORD", "对齐岗位关键词", "原文", "改写后"))));

        ResumeOptimizationAiOutput cleaned = run(output);

        assertEquals(1, cleaned.getChanges().size(), "命中禁用措辞的修改说明必须整条丢弃");
        assertEquals("技能", cleaned.getChanges().get(0).getSection());
    }

    @Test
    void sanitize_truncatesChangeTextFields() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setChanges(new ArrayList<>(List.of(
                change("项目经历", "REWRITE", "理由".repeat(400),
                        "原文".repeat(1500), "改写".repeat(1500)))));

        ResumeOptimizationAiOutput cleaned = run(output);

        ChangeItem item = cleaned.getChanges().get(0);
        // 辅助字段仍是 500；证据字段放宽到 2000（PDF 长段落，见 SanitizedChanges）
        assertEquals(500, item.getReason().length());
        assertEquals(2000, item.getBefore().length());
        assertEquals(2000, item.getAfter().length());
    }

    @Test
    void sanitize_gapQuestionsFilteredAndTruncated() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        output.setGapQuestions(new ArrayList<>(List.of(
                "项目的 QPS 是多少？",
                "  ",
                "项目的 QPS 是多少？",
                "很长的追问".repeat(100))));

        ResumeOptimizationAiOutput cleaned = run(output);

        assertEquals(2, cleaned.getGapQuestions().size());
        assertEquals(200, cleaned.getGapQuestions().get(1).length());
    }

    @Test
    void sanitize_topLevelScoreFieldsAreDropped() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java");
        // 模拟模型试图输出/覆盖分数：输出类型无这些字段，只会被收集
        output.putUnknownField("matchScore", 150);
        output.putUnknownField("scoreAfter", 90);

        ResumeOptimizationAiOutput cleaned = run(output);

        assertTrue(cleaned.drainUnknownFields().isEmpty(), "清洗后约定外字段必须已被取出清空");
    }

    @Test
    void generate_blankContentThrows50201() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("   ");

        BusinessException ex = assertThrows(BusinessException.class, () -> run(output));

        assertEquals(50201, ex.getCode(), "空优化稿必须按输出无法解析处理（不落空记录）");
    }

    // ---------------------------------------------------------------- 清洗 → 真实校验器（不 mock 为恒定通过）

    @Test
    void generate_overlongBeforeEvidence_isRejected() {
        String prefix = "甲".repeat(2001);
        Resume resume = resumeWith(prefix);
        ResumeOptimizationAiOutput output = outputWith("改写后", List.of(
                change("项目经历", "REWRITE", "对齐岗位", prefix + "虚构内容", "改写后")));

        BusinessException ex = assertThrows(BusinessException.class, () -> runReal(resume, output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_overlongAfterEvidence_isRejected() {
        String prefix = "甲".repeat(2001);
        Resume resume = resumeWith(prefix);
        ResumeOptimizationAiOutput output = outputWith(prefix + "虚构", List.of(
                change("项目经历", "REWRITE", "对齐岗位", prefix, prefix + "虚构")));

        BusinessException ex = assertThrows(BusinessException.class, () -> runReal(resume, output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_exactly500Evidence_passes() {
        String prefix = "甲".repeat(500);
        String content = prefix + "旧";
        ResumeOptimizationAiOutput output = outputWith(content, List.of(
                change("项目经历", "REWRITE", "对齐岗位", prefix, prefix)));

        ResumeOptimizationAiOutput result = runReal(resumeWith(content), output).value();

        assertEquals(content, result.getOptimizedContent());
    }

    @Test
    void generate_wordingGuardDroppedChange_isRejected() {
        String resumeContent = "负责 3 个模块，接口平均响应时间为 5 秒。";
        String optimized = "负责 5 个模块，接口平均响应时间为 5 毫秒。";
        ChangeItem valid = change("项目经历", "REWRITE", "对齐岗位",
                "负责 3 个模块", "负责 5 个模块");
        ChangeItem blocked = change("项目经历", "REWRITE", "不具备该能力",
                "接口平均响应时间为 5 秒", "接口平均响应时间为 5 毫秒");
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(valid, blocked));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("信息损失"), ex.getMessage());
    }

    @Test
    void generate_over30Changes_isRejectedWhenContentChanged() {
        String resumeContent = "张三 / Java 后端开发";
        String optimized = resumeContent + "\n补充";
        List<ChangeItem> changes = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            changes.add(change("项目经历", "REWRITE", "对齐岗位", "Java", "Java"));
        }
        ResumeOptimizationAiOutput output = outputWith(optimized, changes);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("信息损失"), ex.getMessage());
    }

    @Test
    void generate_nullChange_isRejectedWithoutNpe() {
        String resumeContent = "张三 / Java 后端开发";
        ResumeOptimizationAiOutput output = outputWith(resumeContent + "\n补充",
                Collections.singletonList(null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_missingSectionChange_isRejectedWhenContentChanged() {
        String resumeContent = "张三 / Java 后端开发";
        String optimized = resumeContent + "\n补充";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change(null, "REWRITE", "对齐岗位", "Java", "Java")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_missingAfterChange_isRejectedWhenContentChanged() {
        String resumeContent = "张三 / Java 后端开发";
        String optimized = resumeContent + "\n补充";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", "Java", null)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_emptyChangesWithContentChanged_isRejected() {
        String resumeContent = "张三 / Java 后端开发";
        ResumeOptimizationAiOutput output = outputWith(resumeContent + "\n补充", List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("逐项修改说明"), ex.getMessage());
    }

    @Test
    void generate_emptyChangesWithContentSame_passes() {
        String resumeContent = "张三 / Java 后端开发";
        ResumeOptimizationAiOutput output = outputWith(resumeContent, List.of());

        ResumeOptimizationAiOutput result = runReal(resumeWith(resumeContent), output).value();

        assertEquals(resumeContent, result.getOptimizedContent());
    }

    // ---------------------------------------------------------------- 数字上下文（修复 B）

    @Test
    void generate_numericContextSwap_isRejected() {
        String resumeContent = "负责 3 个模块，接口平均响应时间为 5 秒。";
        String optimized = "负责 5 个模块，接口平均响应时间为 3 秒。";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数值"), ex.getMessage());
    }

    @Test
    void generate_thousandsSeparatorReformat_passes() {
        String resumeContent = "系统累计处理 1,000 条数据，运行稳定。";
        String optimized = "系统累计处理 1000 条数据，运行稳定。";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        ResumeOptimizationAiOutput result = runReal(resumeWith(resumeContent), output).value();

        assertEquals(optimized, result.getOptimizedContent());
    }

    @Test
    void generate_chineseNumeralChange_isRejected() {
        String resumeContent = "负责三个模块。";
        String optimized = "负责五个模块。";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数字"), ex.getMessage());
    }

    @Test
    void generate_crossProjectSwap_isRejected() {
        String resumeContent = "项目 A：开发 3 个模块\n项目 B：开发 5 个模块";
        String optimized = "项目 A：开发 5 个模块\n项目 B：开发 3 个模块";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数值"), ex.getMessage());
    }

    @Test
    void generate_ambiguousChineseNumeral_isRejected() {
        String resumeContent = "处理约三千五条记录";
        String optimized = "处理约 3005 条记录";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数字"), ex.getMessage());
    }

    @Test
    void generate_crossSectionMove_isRejected() {
        String resumeContent = "## 工作经历\n2025.03-2025.06 在 A 公司实习\n## 项目经历\n智能求职辅助平台";
        String optimized = "## 工作经历\n## 项目经历\n2025.03-2025.06 在 A 公司实习\n智能求职辅助平台";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("工作经历", "REWRITE", "对齐岗位",
                        "2025.03-2025.06 在 A 公司实习", "2025.03-2025.06 在 A 公司实习")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("章节"), ex.getMessage());
    }

    @Test
    void generate_dateRoleChange_isRejected() {
        String resumeContent = "2025年3月入职";
        String optimized = "2025年3月离职";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("工作经历", "REWRITE", "对齐岗位", resumeContent, optimized)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("日期"), ex.getMessage());
    }

    @Test
    void generate_tableToListConversion_passes() {
        String resumeContent = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |";
        String optimized = "### 项目 A\n- 模块数：3\n- 响应时间：54 毫秒";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位",
                        "| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                        "- 模块数：3\n- 响应时间：54 毫秒")));

        ResumeOptimizationAiOutput result = runReal(resumeWith(resumeContent), output).value();

        assertEquals(optimized, result.getOptimizedContent());
    }

    @Test
    void generate_unparsableTableChange_isRejected() {
        String resumeContent = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |";
        String optimized = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 | 额外 |";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of(
                change("项目经历", "REWRITE", "对齐岗位",
                        "| 模块数 | 响应时间 |", "| 模块数 | 响应时间 |")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    @Test
    void generate_ambiguousDuplicateBullets_isRejected() {
        String resumeContent = "### 项目 A\n- 负责接口开发\n- 负责接口开发";
        String optimized = "### 项目 A\n- 负责接口开发";
        ResumeOptimizationAiOutput output = outputWith(optimized, List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> runReal(resumeWith(resumeContent), output));

        assertEquals(50201, ex.getCode());
        assertTrue(ex.getMessage().contains("事实校验"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 辅助

    private ResumeOptimizationAiOutput run(ResumeOptimizationAiOutput output) {
        when(template.call(eq("resume-optimization"), anyString(), anyString(),
                eq(ResumeOptimizationAiOutput.class)))
                .thenReturn(new AiCallResult<>(output, "test-model", 100L, 1, 1, 2));
        return service.generate(resume(), new JobAnalysis(), new ResumeAnalysis(), scoring()).value();
    }

    /** 每次调用返回一份新输出：清洗会原地修改 output，重试不能复用同一对象 */
    private void stubGenerated(ResumeOptimizationAiOutput output) {
        when(template.call(eq("resume-optimization"), anyString(), anyString(),
                eq(ResumeOptimizationAiOutput.class)))
                .thenAnswer(invocation -> new AiCallResult<>(
                        copyOf(output), "test-model", 100L, 1, 1, 2));
    }

    private static ResumeOptimizationAiOutput copyOf(ResumeOptimizationAiOutput source) {
        ResumeOptimizationAiOutput copy = new ResumeOptimizationAiOutput();
        copy.setOptimizedContent(source.getOptimizedContent());
        copy.setChanges(source.getChanges() == null ? null : new ArrayList<>(source.getChanges()));
        copy.setGapQuestions(source.getGapQuestions() == null
                ? null : new ArrayList<>(source.getGapQuestions()));
        return copy;
    }

    /** 真实校验器（不是 setUp 中恒定通过的 mock），用于清洗→校验交互测试 */
    private ResumeOptimizationAiService realCheckerService() {
        return new ResumeOptimizationAiService(template, new ResumeOptimizationFactChecker(),
                new ClassPathResource("prompts/resume-optimization.system.txt"));
    }

    private AiCallResult<ResumeOptimizationAiOutput> runReal(Resume resume,
                                                             ResumeOptimizationAiOutput output) {
        // 事实校验失败会触发一次自动重试，每次都必须返回新副本：
        // 清洗会原地修改 output，复用同一对象会让第二次的清洗计数失真。
        when(template.call(eq("resume-optimization"), anyString(), anyString(),
                eq(ResumeOptimizationAiOutput.class)))
                .thenAnswer(invocation -> new AiCallResult<>(
                        copyOf(output), "test-model", 100L, 1, 1, 2));
        return realCheckerService().generate(resume, new JobAnalysis(), new ResumeAnalysis(), scoring());
    }

    private static Resume resumeWith(String content) {
        Resume resume = resume();
        resume.setContent(content);
        return resume;
    }

    private static ResumeOptimizationAiOutput outputWith(String optimized, List<ChangeItem> changes) {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent(optimized);
        output.setChanges(changes);
        return output;
    }

    private static Resume resume() {
        Resume resume = new Resume();
        resume.setContent("张三 / Java 后端开发\n技能：Java、Spring Boot");
        return resume;
    }

    private static ChangeItem change(String section, String type, String reason,
                                     String before, String after) {
        ChangeItem item = new ChangeItem();
        item.setSection(section);
        item.setType(type);
        item.setReason(reason);
        item.setBefore(before);
        item.setAfter(after);
        return item;
    }

    private static MatchScoringResult scoring() {
        List<MatchDimensionScore> items = List.of(
                new MatchDimensionScore("requiredSkills", "必备技能", 45, 80));
        return new MatchScoringResult(new BigDecimal("80.00"),
                new MatchDimensionScores("v1", items), List.of());
    }

}
