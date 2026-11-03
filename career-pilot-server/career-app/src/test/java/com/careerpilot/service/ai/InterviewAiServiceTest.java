package com.careerpilot.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.careerpilot.dto.ai.GeneratedQuestion;
import com.careerpilot.dto.ai.InterviewEvaluationAiOutput;
import com.careerpilot.dto.ai.InterviewQuestionsAiOutput;
import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.entity.InterviewQuestion;
import com.careerpilot.entity.Job;
import com.careerpilot.service.skill.InterviewSkillService;

/**
 * 面试三个场景的输出清洗护栏（《Agent设计》§4.6「面试出题 / 面试评价」两行 + 总评）。
 *
 * 覆盖硬要求：空题/重复题过滤、题干截断、类别白名单外置 null、0 题 50201、
 * 多于计划数截断、score 缺失 50201 与越界钳制、文本与列表截断。
 */
class InterviewAiServiceTest {

    private AiCallTemplate template;

    private InterviewAiService service;

    @BeforeEach
    void setUp() {
        template = mock(AiCallTemplate.class);
        // M15：技能服务 mock——本类只验证输出清洗，不依赖技能文件内容
        // （技能加载与题量分配算法的测试见 InterviewSkillServiceTest）
        InterviewSkillService skillService = mock(InterviewSkillService.class);
        when(skillService.calculateAllocation(anyString(), anyInt())).thenReturn(Collections.emptyMap());
        when(skillService.currentSkill()).thenReturn(
                new InterviewSkillService.SkillDefinition("test-skill", "", List.of()));
        when(skillService.buildReferenceSection(any())).thenReturn("");
        // 读真实提示词资源：模板缺失同样让测试失败（与"启动即失败"的约定一致）
        service = new InterviewAiService(template, skillService,
                new ClassPathResource("prompts/interview-question.system.txt"),
                new ClassPathResource("prompts/interview-evaluation.system.txt"),
                new ClassPathResource("prompts/interview-summary.system.txt"));
    }

    // ---------------------------------------------------------------- 出题

    @Test
    void generate_dropsBlankQuestions() {
        InterviewQuestionsAiOutput output = questions(
                question("Spring 的依赖注入是什么？", "SPRING"),
                question("   ", "JAVA"),
                question(null, null));

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 5);

        assertEquals(1, cleaned.getQuestions().size(), "空题与纯空白题必须被过滤");
    }

    @Test
    void generate_deduplicatesByNormalizedText() {
        InterviewQuestionsAiOutput output = questions(
                question("介绍 MySQL 的索引结构", "MYSQL"),
                question("介绍  MySQL  的索引结构", "MYSQL"),   // 折叠空白后与上题相同
                question("介绍 MySQL 的存储引擎", "MYSQL"));

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 5);

        assertEquals(2, cleaned.getQuestions().size(), "折叠空白后全等的题目必须去重");
    }

    @Test
    void generate_truncatesLongQuestion() {
        InterviewQuestionsAiOutput output = questions(question("很长的题目".repeat(600), "JAVA"));

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 5);

        assertEquals(2000, cleaned.getQuestions().get(0).getQuestion().length());
    }

    @Test
    void generate_categoryOutOfRangeBecomesNullAndValidKept() {
        InterviewQuestionsAiOutput output = questions(
                question("题目一", "KOTLIN"),
                question("题目二", "SPRING"));

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 5);

        assertNull(cleaned.getQuestions().get(0).getQuestionCategory(), "越界类别必须置 null 而非丢弃整题");
        assertEquals("SPRING", cleaned.getQuestions().get(1).getQuestionCategory());
    }

    @Test
    void generate_truncatesWhenMoreThanPlanned() {
        InterviewQuestionsAiOutput output = questions(
                question("题目一", "JAVA"), question("题目二", "JAVA"), question("题目三", "JAVA"));

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 2);

        assertEquals(2, cleaned.getQuestions().size(), "多于计划数时截断到 questionCount");
    }

    @Test
    void generate_zeroQuestionsAfterCleaningThrows50201() {
        InterviewQuestionsAiOutput output = questions(question("  ", "JAVA"));

        BusinessException ex = assertThrows(BusinessException.class, () -> runQuestions(output, 5));

        assertEquals(50201, ex.getCode(), "清洗后 0 题必须按 50201 处理（不产生空会话）");
    }

    @Test
    void generate_dropsUnknownFieldsOnElements() {
        GeneratedQuestion item = question("题目", "JAVA");
        item.putUnknownField("referenceAnswer", "模型多给的参考答案");
        item.putUnknownField("考察点", "多给的考察点");
        InterviewQuestionsAiOutput output = questions(item);

        InterviewQuestionsAiOutput cleaned = runQuestions(output, 5);

        assertTrue(cleaned.getQuestions().get(0).drainUnknownFields().isEmpty(),
                "元素内约定外字段必须已被取出清空（参考答案在评价时才产生）");
    }

    // ---------------------------------------------------------------- 出题上下文块（M12）

    @Test
    void generate_withContext_appendsResumeAndGapBlocks() {
        String prompt = capturedPrompt("张三的简历：熟悉 Java 与 Spring Boot",
                List.of(gap("简历未体现 Redis 使用经验")));

        assertTrue(prompt.contains("<候选人简历>"), "携带简历时必须出现候选人简历块");
        assertTrue(prompt.contains("熟悉 Java 与 Spring Boot"));
        assertTrue(prompt.contains("简历未体现 Redis 使用经验"), "差距项必须进入提示词");
    }

    @Test
    void generate_withoutContext_noContextBlocks() {
        String prompt = capturedPrompt(null, null);

        assertFalse(prompt.contains("候选人简历"), "未携带上下文时不得出现空块（与历史逐字节一致）");
        assertFalse(prompt.contains("能力差距"));
    }

    @Test
    void generate_truncatesResumeTextToLimit() {
        String longResume = "简历".repeat(3000);   // 6000 字符，超出 4000 上限

        String prompt = capturedPrompt(longResume, null);

        assertFalse(prompt.contains(longResume), "超长简历不得整段进入提示词");
        assertTrue(prompt.contains("…"), "简历节选超长时截断");
    }

    // ---------------------------------------------------------------- 评价

    @Test
    void evaluate_missingScoreThrows50201() {
        InterviewEvaluationAiOutput output = evaluation(null, "点评", "参考", List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> runEvaluation(output));

        assertEquals(50201, ex.getCode(), "score 缺失必须按 50201 处理");
    }

    @Test
    void evaluate_clampsScoreToBounds() {
        assertEquals(new BigDecimal("100.00"), runEvaluation(evaluation(new BigDecimal("150"), null, null, null)).getScore());
        assertEquals(new BigDecimal("0.00"), runEvaluation(evaluation(new BigDecimal("-5"), null, null, null)).getScore());
    }

    @Test
    void evaluate_scalesScoreToTwoDecimals() {
        InterviewEvaluationAiOutput cleaned = runEvaluation(
                evaluation(new BigDecimal("78.333"), null, null, null));

        assertEquals(new BigDecimal("78.33"), cleaned.getScore(), "分数统一两位小数（HALF_UP），与库列对齐");
    }

    @Test
    void evaluate_truncatesTextsAndSuggestions() {
        InterviewEvaluationAiOutput output = evaluation(new BigDecimal("70"),
                "很长的点评".repeat(600), "很长的参考答案".repeat(600),
                new ArrayList<>(Collections.nCopies(25, "建议")));

        InterviewEvaluationAiOutput cleaned = runEvaluation(output);

        assertEquals(2000, cleaned.getAnalysis().length());
        assertEquals(2000, cleaned.getReferenceAnswer().length());
        assertEquals(20, cleaned.getSuggestions().size());
    }

    // ---------------------------------------------------------------- 总评

    @Test
    void summarize_truncatesSummaryAndTagLists() {
        InterviewSummaryAiOutput output = new InterviewSummaryAiOutput();
        output.setSummary("很长的总评".repeat(600));
        output.setStrengths(new ArrayList<>(Collections.nCopies(15, "强项")));
        output.setWeaknesses(new ArrayList<>(Collections.nCopies(15, "薄弱")));
        output.setRecommendations(new ArrayList<>(Collections.nCopies(15, "建议")));

        InterviewSummaryAiOutput cleaned = runSummary(output);

        assertEquals(2000, cleaned.getSummary().length());
        assertEquals(10, cleaned.getStrengths().size());
        assertEquals(10, cleaned.getWeaknesses().size());
        assertEquals(10, cleaned.getRecommendations().size());
    }

    // ---------------------------------------------------------------- 辅助

    private InterviewQuestionsAiOutput runQuestions(InterviewQuestionsAiOutput output, int count) {
        when(template.call(eq("interview-question"), anyString(), anyString(), eq(InterviewQuestionsAiOutput.class)))
                .thenReturn(new AiCallResult<>(output, "test-model", 100L, 1, 1, 2));
        return service.generate(job(), null, "MIXED", count, null, null).value();
    }

    /** 捕获出题场景的用户消息（M12 上下文块断言用） */
    private String capturedPrompt(String resumeText, List<MatchItem> gapItems) {
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(template.call(eq("interview-question"), anyString(), userPrompt.capture(),
                eq(InterviewQuestionsAiOutput.class)))
                .thenReturn(new AiCallResult<>(questions(question("题目", "JAVA")),
                        "test-model", 100L, 1, 1, 2));
        service.generate(job(), null, "MIXED", 3, resumeText, gapItems);
        return userPrompt.getValue();
    }

    private static MatchItem gap(String text) {
        MatchItem item = new MatchItem();
        item.setText(text);
        return item;
    }

    private InterviewEvaluationAiOutput runEvaluation(InterviewEvaluationAiOutput output) {
        when(template.call(eq("interview-evaluation"), anyString(), anyString(), eq(InterviewEvaluationAiOutput.class)))
                .thenReturn(new AiCallResult<>(output, "test-model", 100L, 1, 1, 2));
        return service.evaluate(job(), questionEntity(), "我的回答").value();
    }

    private InterviewSummaryAiOutput runSummary(InterviewSummaryAiOutput output) {
        when(template.call(eq("interview-summary"), anyString(), anyString(), eq(InterviewSummaryAiOutput.class)))
                .thenReturn(new AiCallResult<>(output, "test-model", 100L, 1, 1, 2));
        return service.summarize(job(), "MIXED", List.of(questionEntity()), new BigDecimal("70.00")).value();
    }

    private static Job job() {
        Job job = new Job();
        job.setId(1L);
        job.setJobName("Java 后端开发实习生");
        job.setDescription("熟悉 Java 与 Spring Boot");
        return job;
    }

    private static InterviewQuestion questionEntity() {
        InterviewQuestion question = new InterviewQuestion();
        question.setId(1L);
        question.setQuestion("请解释 Spring 的依赖注入");
        question.setQuestionCategory("SPRING");
        return question;
    }

    private static InterviewQuestionsAiOutput questions(GeneratedQuestion... items) {
        InterviewQuestionsAiOutput output = new InterviewQuestionsAiOutput();
        output.setQuestions(new ArrayList<>(List.of(items)));
        return output;
    }

    private static GeneratedQuestion question(String text, String category) {
        GeneratedQuestion item = new GeneratedQuestion();
        item.setQuestion(text);
        item.setQuestionCategory(category);
        return item;
    }

    private static InterviewEvaluationAiOutput evaluation(BigDecimal score, String analysis,
                                                          String referenceAnswer, List<String> suggestions) {
        InterviewEvaluationAiOutput output = new InterviewEvaluationAiOutput();
        output.setScore(score);
        output.setAnalysis(analysis);
        output.setReferenceAnswer(referenceAnswer);
        output.setSuggestions(suggestions);
        return output;
    }

}
