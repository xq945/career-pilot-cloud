package com.careerpilot.service.optimization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.ResumeOptimizationFactChecker.Category;
import com.careerpilot.service.optimization.ResumeOptimizationFactChecker.Result;

/**
 * 事实校验器的最小安全边界（第三阶段，O-02）。
 *
 * <p>测试使用固定文本与 Mock 输出，不调用模型。重点验证：
 * 原文证据、正文一致性、新增数字/专名/技术名词风险、规范化边界与消息脱敏。
 */
class ResumeOptimizationFactCheckerTest {

    private final ResumeOptimizationFactChecker checker = new ResumeOptimizationFactChecker();

    private static final String RESUME = """
            基本信息
            姓名：张三  电话：13800000000  邮箱：zhangsan@example.com
            教育：江西师范大学 数据科学与大数据技术 2027届本科
            实习：江西云牛科技 2026.09-至今
            项目：智能求职辅助平台 2026.07-2026.10
            技术栈：Java 21、Spring Boot、MySQL、Redis、MyBatis
            成果：接口 54 毫秒返回
            证书：中级软件设计师
            """;

    private static final String OPTIMIZED = """
            姓名：张三
            教育：江西师范大学 数据科学与大数据技术 2027届本科
            技术栈：Java 21、Spring Boot、MySQL、Redis、MyBatis
            项目：智能求职辅助平台 2026.07-2026.10
            成果：接口 54 毫秒返回
            实习：江西云牛科技 2026.09-至今
            证书：中级软件设计师证书
            """;

    private static final ChangeItem VALID_CHANGE =
            change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回");

    // ---------------------------------------------------------------- 正向基线

    @Test
    void validOutput_passes() {
        Result result = checker.check(RESUME, OPTIMIZED, List.of(VALID_CHANGE));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void formatOnlyChanges_doNotCountAsNewFacts() {
        String rephrased = OPTIMIZED.replace("成果：接口 54 毫秒返回", "成果：接口响应耗时 54 毫秒");
        Result result = checker.check(RESUME, rephrased,
                List.of(change("成果：接口 54 毫秒返回", "成果：接口响应耗时 54 毫秒")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void existingNumberReformatted_isNotReported() {
        String formatted = OPTIMIZED.replace("2026.09-至今", "2026年9月至今");
        Result result = checker.check(RESUME, formatted,
                List.of(change("实习：江西云牛科技 2026.09-至今", "实习：江西云牛科技 2026年9月至今")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void techTermCaseAndSpacingChange_isSupportedByOriginal() {
        String compact = OPTIMIZED.replace("Spring Boot", "SpringBoot");
        Result result = checker.check(RESUME, compact,
                List.of(change("技术栈：Java 21、Spring Boot、MySQL、Redis、MyBatis",
                        "技术栈：Java 21、SpringBoot、MySQL、Redis、MyBatis")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    // ---------------------------------------------------------------- 原文证据

    @Test
    void beforeWithWhitespaceAndMarkdownPrefix_passes() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("## 成果：接口  54\t毫秒返回", "成果：接口 54 毫秒返回")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void beforeNotInResume_isRejected() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("熟悉 Kubernetes 与 Docker", "成果：接口 54 毫秒返回")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.BEFORE_NOT_IN_RESUME));
    }

    @Test
    void promptTagPrefix_isStrippedBeforeEvidenceCheck() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("【简历原文】\n基本信息", "成果：接口 54 毫秒返回")));

        assertFalse(hasCategory(result, Category.BEFORE_NOT_IN_RESUME),
                () -> result.issues().toString());
    }

    @Test
    void promptTagOnlyBefore_isStillRejected() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("【简历原文】", "成果：接口 54 毫秒返回")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.BEFORE_NOT_IN_RESUME));
    }

    @Test
    void jdTextAsBefore_isRejected() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("熟悉 java 编程、理解 IO、多线程等基础能力", "成果：接口 54 毫秒返回")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.BEFORE_NOT_IN_RESUME));
    }

    @Test
    void emptyResume_isRejectedWithoutSkippingChecks() {
        Result nullResume = checker.check(null, OPTIMIZED, List.of(VALID_CHANGE));
        Result blankResume = checker.check("   ", OPTIMIZED, List.of(VALID_CHANGE));

        assertFalse(nullResume.passed());
        assertFalse(blankResume.passed());
        assertTrue(hasCategory(nullResume, Category.RESUME_CONTENT_MISSING));
        assertTrue(hasCategory(blankResume, Category.RESUME_CONTENT_MISSING));
    }

    // ---------------------------------------------------------------- 正文一致性

    @Test
    void afterNotInOptimized_isRejected() {
        Result result = checker.check(RESUME, OPTIMIZED,
                List.of(change("成果：接口 54 毫秒返回", "提升 30% 的响应速度")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.AFTER_NOT_IN_OPTIMIZED));
    }

    @Test
    void changesEmptyButContentChanged_isRejected() {
        Result result = checker.check(RESUME, OPTIMIZED, List.of());

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.CHANGES_EMPTY_BUT_CONTENT_CHANGED));
    }

    @Test
    void changesEmptyAndContentFormatOnly_passes() {
        String sameContent = RESUME.replace("\n", "\n\n");

        Result result = checker.check(RESUME, sameContent, null);

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void blankBeforeOrAfter_isRejected() {
        Result blankBefore = checker.check(RESUME, OPTIMIZED,
                List.of(change("", "成果：接口 54 毫秒返回")));
        Result blankAfter = checker.check(RESUME, OPTIMIZED,
                List.of(change("成果：接口 54 毫秒返回", "")));

        assertFalse(blankBefore.passed());
        assertTrue(hasCategory(blankBefore, Category.BEFORE_NOT_IN_RESUME));
        assertFalse(blankAfter.passed());
        assertTrue(hasCategory(blankAfter, Category.AFTER_NOT_IN_OPTIMIZED));
    }

    @Test
    void trimChangeStillRequiresLocatableAfter() {
        ChangeItem trim = change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回");
        trim.setType("TRIM");
        Result pass = checker.check(RESUME, OPTIMIZED, List.of(trim));
        assertTrue(pass.passed(), () -> pass.issues().toString());

        // 当前业务语义不保留“无 after 的删除”：模型若只写删除标记，无法与正文核对
        ChangeItem deletionNote = change("成果：接口 54 毫秒返回", "（已删除）");
        deletionNote.setType("TRIM");
        Result fail = checker.check(RESUME, OPTIMIZED, List.of(deletionNote));
        assertFalse(fail.passed());
        assertTrue(hasCategory(fail, Category.AFTER_NOT_IN_OPTIMIZED));
    }

    // ---------------------------------------------------------------- 新增事实风险

    @Test
    void newQuantifiedClaim_isReportedAsRisk() {
        String optimized = OPTIMIZED + "\n提升 30% 的响应速度";
        Result result = checker.check(RESUME, optimized,
                List.of(change("成果：接口 54 毫秒返回", "提升 30% 的响应速度")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_NUMBER));
    }

    @Test
    void newCompany_isReportedAsRisk() {
        String optimized = OPTIMIZED + "\n实习新增：阿里巴巴公司";
        Result result = checker.check(RESUME, optimized,
                List.of(change("实习：江西云牛科技 2026.09-至今", "实习新增：阿里巴巴公司")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_PROPER_NOUN));
    }

    @Test
    void newSchool_isReportedAsRisk() {
        String optimized = OPTIMIZED + "\n教育新增：清华大学";
        Result result = checker.check(RESUME, optimized,
                List.of(change("教育：江西师范大学 数据科学与大数据技术 2027届本科", "教育新增：清华大学")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_PROPER_NOUN));
    }

    @Test
    void newCertificate_isReportedAsRisk() {
        String optimized = OPTIMIZED + "\n证书新增：AWS认证";
        Result result = checker.check(RESUME, optimized,
                List.of(change("证书：中级软件设计师", "证书新增：AWS认证")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_PROPER_NOUN));
    }

    @Test
    void newTechTerm_isReportedAsRisk() {
        String optimized = OPTIMIZED + "\n技术栈新增：Kubernetes";
        Result result = checker.check(RESUME, optimized,
                List.of(change("技术栈：Java 21、Spring Boot、MySQL、Redis、MyBatis",
                        "技术栈新增：Kubernetes")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_TECH_TERM));
    }

    @Test
    void responsibilityEscalation_isReportedAsRisk() {
        String resume = "项目：智能求职辅助平台\n职责：参与接口开发";
        String optimized = "项目：智能求职辅助平台\n职责：主导接口开发";
        Result result = checker.check(resume, optimized,
                List.of(change("职责：参与接口开发", "职责：主导接口开发")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_ACHIEVEMENT));
    }

    @Test
    void newProjectHeading_isReportedAsRisk() {
        String resume = "## 项目经历\n### 智能求职辅助平台 | 2026.07-2026.10\n- 参与接口开发";
        String optimized = "## 项目经历\n### 电商平台 | 2026.07-2026.10\n- 参与接口开发";
        Result result = checker.check(resume, optimized,
                List.of(change("参与接口开发", "参与接口开发")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_HEADING_ENTITY));
    }

    @Test
    void failureMessage_doesNotLeakResumeContentOrNewValue() {
        String optimized = OPTIMIZED + "\n提升 30% 的响应速度";
        Result result = checker.check(RESUME, optimized,
                List.of(change("成果：接口 54 毫秒返回", "提升 30% 的响应速度")));

        assertFalse(result.passed());
        assertFalse(result.userMessage().contains("13800000000"));
        assertFalse(result.userMessage().contains("张三"));
        assertFalse(result.userMessage().contains("30"));
        assertFalse(result.userMessage().contains("Kubernetes"));
        assertTrue(result.userMessage().length() <= 480, "失败原因必须适配 error_message 的 500 字符上限");
    }

    // ---------------------------------------------------------------- 证据强度与清洗完整性（修复 A）

    @Test
    void punctuationOnlyBefore_isRejected() {
        Result dash = checker.check(RESUME, OPTIMIZED,
                List.of(change("-", "成果：接口 54 毫秒返回")));
        Result dot = checker.check(RESUME, OPTIMIZED,
                List.of(change(".", "成果：接口 54 毫秒返回")));

        assertFalse(dash.passed());
        assertTrue(hasCategory(dash, Category.WEAK_EVIDENCE));
        assertFalse(dot.passed());
        assertTrue(hasCategory(dot, Category.WEAK_EVIDENCE));
    }

    @Test
    void shortQuantifiedBefore_isRejected() {
        String resume = "负责 3 个模块，接口平均响应时间为 5 秒。";
        String optimized = "负责 5 个模块，接口平均响应时间为 3 秒。";

        Result result = checker.check(resume, optimized,
                List.of(change("5 秒", "5 个模块")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.WEAK_EVIDENCE));
    }

    @Test
    void shortAsciiTechnicalToken_isAllowed() {
        String resume = "使用 AI Agent 开发。";
        String optimized = "使用 AI Agent 开发。";

        Result result = checker.check(resume, optimized,
                List.of(change("AI", "AI")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void shortDateAndVersionEvidence_areAllowed() {
        String resume = "实习 2026.09 开始。版本 1.0。";

        Result date = checker.check(resume, resume, List.of(change("2026.09", "2026.09")));
        Result version = checker.check(resume, resume, List.of(change("1.0", "1.0")));

        assertTrue(date.passed(), () -> date.issues().toString());
        assertTrue(version.passed(), () -> version.issues().toString());
    }

    @Test
    void singleLetterTechnicalToken_isAllowed() {
        String resume = "技能：C 语言。";
        String optimized = "技能：C 语言。";

        Result result = checker.check(resume, optimized,
                List.of(change("C", "C")));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void shortAsciiTokenWithoutBoundary_isRejected() {
        String resume = "使用 A B 开发。";
        String optimized = "使用 A B 开发。";

        Result result = checker.check(resume, optimized,
                List.of(change("AB", "AB")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.WEAK_EVIDENCE));
    }

    @Test
    void ambiguousShortBefore_isRejected() {
        String resume = "Java 开发。Java 测试。";
        String optimized = "Java 开发。Java 测试。";

        Result result = checker.check(resume, optimized,
                List.of(change("Java", "Java")));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.AMBIGUOUS_EVIDENCE));
    }

    @Test
    void exact500Evidence_passes() {
        String prefix = "甲".repeat(500);
        String content = prefix + "旧";

        Result result = checker.check(content, content,
                List.of(change(prefix, prefix)));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void overlongEvidence_isRejectedEvenIfPrefixExists() {
        String prefix = "甲".repeat(2001);
        String content = prefix + "旧";
        ChangeItem overlong = change(prefix + "虚构内容", prefix);

        Result result = checker.check(content, content, List.of(overlong));

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.EVIDENCE_TOO_LONG));
    }

    @Test
    void informationLossWithContentChanged_isRejected() {
        SanitizedChanges loss = new SanitizedChanges(
                List.of(change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回")),
                2, 1, 0, 0, 0, 1, 0, false, false);

        Result result = checker.checkWithIntegrity(RESUME, OPTIMIZED, loss);

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.CHANGES_INCOMPLETE));
    }

    @Test
    void informationLossWithContentSame_passes() {
        SanitizedChanges loss = new SanitizedChanges(
                List.of(change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回")),
                2, 1, 0, 0, 0, 1, 0, false, false);

        Result result = checker.checkWithIntegrity(RESUME, RESUME, loss);

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void overLimitChangesWithContentChanged_isRejected() {
        SanitizedChanges overLimit = new SanitizedChanges(
                List.of(change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回")),
                31, 1, 0, 0, 0, 0, 30, false, false);

        Result result = checker.checkWithIntegrity(RESUME, OPTIMIZED, overLimit);

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.CHANGES_INCOMPLETE));
    }

    @Test
    void nullElement_isRejected() {
        SanitizedChanges withNull = new SanitizedChanges(
                List.of(change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回")),
                2, 2, 1, 0, 0, 0, 0, false, false);

        Result result = checker.checkWithIntegrity(RESUME, RESUME, withNull);

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.INVALID_STRUCTURE));
    }

    @Test
    void evidenceTooLongFlag_isRejected() {
        SanitizedChanges tooLong = new SanitizedChanges(
                List.of(change("成果：接口 54 毫秒返回", "成果：接口 54 毫秒返回")),
                1, 1, 0, 0, 0, 0, 0, true, false);

        Result result = checker.checkWithIntegrity(RESUME, RESUME, tooLong);

        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.EVIDENCE_TOO_LONG));
    }

    // ---------------------------------------------------------------- 数字上下文（修复 B）

    @Test
    void numericContextSwap_equalNumberSet_isRejected() {
        String resume = "负责 3 个模块，接口平均响应时间为 5 秒。";
        String optimized = "负责 5 个模块，接口平均响应时间为 3 秒。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
        assertFalse(hasCategory(result, Category.NEW_NUMBER),
                "3/5 都在原文出现，不能误报为新增数字");
    }

    @Test
    void userCountMagnitudeFormat_passes() {
        String resume = "用户数 3 万，订单数 5 千。";
        String optimized = "用户数 30000，订单数 5000。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void userCountValueChange_isRejected() {
        String resume = "用户数 3 万，订单数 5 千。";
        String optimized = "用户数 5 万，订单数 5 千。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NEW_NUMBER));
    }

    @Test
    void conjunctionObjectSwap_isRejected() {
        String resume = "负责 3 个模块和 5 个接口。";
        String optimized = "负责 5 个模块和 3 个接口。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
        assertFalse(hasCategory(result, Category.NEW_NUMBER));
    }

    @Test
    void conjunctionObjectUnchanged_passes() {
        String resume = "负责 3 个模块和 5 个接口。";

        Result result = checker.check(resume, resume, List.of(change(resume, resume)));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void responseTimeAlias_passes() {
        String resume = "接口响应时间 5 秒。";
        String optimized = "接口响应耗时 5 秒。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void metricObjectChange_isRejected() {
        String resume = "转化率提升 30%。";
        String optimized = "成本降低 30%。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    @Test
    void chineseNumeralEquivalence_passesAndValueChangeFails() {
        String resume = "负责三个模块。";
        String equivalent = "负责 3 个模块。";
        Result pass = checker.check(resume, equivalent, List.of(change(resume, equivalent)));
        assertEvidenceClean(pass);
        assertTrue(pass.passed(), () -> pass.issues().toString());

        String changed = "负责五个模块。";
        Result fail = checker.check(resume, changed, List.of(change(resume, changed)));
        assertEvidenceClean(fail);
        assertFalse(fail.passed());
        assertTrue(hasCategory(fail, Category.NEW_NUMBER));
    }

    @Test
    void thousandsSeparator_passes() {
        String resume = "系统累计处理 1,000 条数据，运行稳定。";
        String optimized = "系统累计处理 1000 条数据，运行稳定。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void dateAndVersionFormats_doNotBecomeBusinessNumbers() {
        String resume = "2025 年 3 月入职，负责 3 个模块。技术栈：Java 17、Spring Boot 3.5。";
        String optimized = "2025年3月入职，负责 3 个模块。技术栈：Java 17、SpringBoot 3.5。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void bareNumbersUnchanged_passButBareNumbersWithChangedContextAreRejected() {
        String unchanged = "3 个模块，5 秒，30%。";
        Result pass = checker.check(unchanged, unchanged, List.of(change(unchanged, unchanged)));
        assertEvidenceClean(pass);
        assertTrue(pass.passed(), () -> pass.issues().toString());

        String resume = "本季度整体达成 30%。";
        String optimized = "本季度成本降低 30%。";
        Result fail = checker.check(resume, optimized, List.of(change(resume, optimized)));
        assertEvidenceClean(fail);
        assertFalse(fail.passed());
        assertTrue(hasCategory(fail, Category.NUMBER_CONTEXT_AMBIGUOUS));
    }

    @Test
    void qualifierChange_isRejected() {
        String resume = "本季度指标提升约 30%。";
        String optimized = "本季度指标提升 30%。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    @Test
    void duplicateNumberWithDifferentContext_isNotMatchedArbitrarily() {
        String resume = "模块数 3 个。接口数 3 个。";
        String optimized = "项目数 3 个。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH)
                || hasCategory(result, Category.NUMBER_CONTEXT_AMBIGUOUS));
    }

    @Test
    void duplicateOccurrenceIncrease_isRejected() {
        String resume = "模块数 3 个。";
        String optimized = "模块数 3 个。模块数 3 个。";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    @Test
    void signedDecimalPercentFormatsPassAndValueChangesFail() {
        String resume = "本季度利润率 -5%，工作 1.5 年，核心指标提升 30%。";
        String formatted = "本季度利润率 -5%，工作 1.50 年，核心指标提升 30％。";
        Result pass = checker.check(resume, formatted, List.of(change(resume, formatted)));
        assertEvidenceClean(pass);
        assertTrue(pass.passed(), () -> pass.issues().toString());

        String changed = "本季度利润率 5%，工作 1.6 年，核心指标提升 35%。";
        Result fail = checker.check(resume, changed, List.of(change(resume, changed)));
        assertEvidenceClean(fail);
        assertFalse(fail.passed());
        assertTrue(hasCategory(fail, Category.NEW_NUMBER));
    }

    // ---------------------------------------------------------------- Repair B：P0 反例

    @Test
    void dataVsDatabaseWithSameNumber_isRejected() {
        String resume = "处理了 3 个数据";
        String optimized = "处理了 3 个数据库";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    @Test
    void crossProjectValueSwap_isRejectedEvenWhenChangeEvidenceLooksClean() {
        String resume = "项目 A：开发 3 个模块\n项目 B：开发 5 个模块";
        String optimized = "项目 A：开发 5 个模块\n项目 B：开发 3 个模块";
        // 该 change 的 before/after 都能定位，且局部文本形状不同；只能靠文档级项目上下文发现互换
        ChangeItem item = change("项目 A：开发 3 个模块", "项目 B：开发 3 个模块");

        Result result = checker.check(resume, optimized, List.of(item));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    @Test
    void ambiguousChineseNumeral_isRejected() {
        String resume = "处理约三千五条记录";
        String optimized = "处理约 3005 条记录";

        Result result = checker.check(resume, optimized, List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertFalse(result.userMessage().contains("3005"));
    }

    @Test
    void tableHeaderReorderWithUnchangedDataRow_isRejected() {
        String resume = "| 模块数 | 接口数 |\n| --- | --- |\n| 3 | 5 |";
        String optimized = "| 接口数 | 模块数 |\n| --- | --- |\n| 3 | 5 |";
        ChangeItem item = change("| 模块数 | 接口数 |", "| 接口数 | 模块数 |");

        Result result = checker.check(resume, optimized, List.of(item));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.NUMBER_CONTEXT_MISMATCH));
    }

    // ---------------------------------------------------------------- Repair C：正文覆盖与日期角色

    @Test
    void newProjectHiddenByValidUnrelatedChange_isRejected() {
        String resume = "技能：Java\n项目：智能求职辅助平台\n参与接口开发";
        String optimized = "技能：Java\n项目：电商交易系统\n参与接口开发";
        ChangeItem unrelated = change("技能：Java", "技能：Java");

        Result result = checker.check(resume, optimized, List.of(unrelated));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.COVERAGE_NEW_CONTENT_WORDING)
                        || hasCategory(result, Category.COVERAGE_UNCOVERED_DIFF),
                () -> result.issues().toString());
    }

    @Test
    void dateEventRoleChange_isRejected() {
        String resume = "2025年3月入职";
        String optimized = "2025年3月离职";

        Result result = checker.check(resume, optimized,
                List.of(change(resume, optimized)));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.DATE_EVENT_ROLE_MISMATCH),
                () -> result.issues().toString());
    }

    @Test
    void tableToListConversion_passes() {
        String resume = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |";
        String optimized = "### 项目 A\n- 模块数：3\n- 响应时间：54 毫秒";
        ChangeItem item = change(
                "| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "- 模块数：3\n- 响应时间：54 毫秒");

        Result result = checker.check(resume, optimized, List.of(item));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void legalProjectTitleRename_passes() {
        String resume = "### 智能求职辅助平台\n开发 3 个模块";
        String optimized = "### 智能求职辅助平台（重构）\n开发 3 个模块";

        Result result = checker.check(resume, optimized,
                List.of(change("### 智能求职辅助平台", "### 智能求职辅助平台（重构）")));

        assertEvidenceClean(result);
        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void plainTextResumeToMarkdownOptimization_passes() {
        String resume = """
                基本信息
                · 姓名：张三
                · 学历：2027届本科
                实习经历
                · 江西云牛科技（后端开发） 2026.09-至今
                1. 参与接口开发
                """;
        String optimized = """
                ## 基本信息
                - 姓名：张三
                - 学历：2027届本科
                ## 实习经历
                ### 江西云牛科技 | 后端开发 | 2026.09-至今
                1. 参与接口开发
                """;
        ChangeItem title = change("· 江西云牛科技（后端开发） 2026.09-至今",
                "### 江西云牛科技 | 后端开发 | 2026.09-至今");

        Result result = checker.check(resume, optimized, List.of(title));

        assertTrue(result.passed(), () -> result.issues().toString());
    }

    @Test
    void crossSectionTenureMove_isRejected() {
        String resume = "## 工作经历\n2025.03-2025.06 在 A 公司实习\n## 项目经历\n智能求职辅助平台";
        String optimized = "## 工作经历\n## 项目经历\n2025.03-2025.06 在 A 公司实习\n智能求职辅助平台";
        ChangeItem item = change("2025.03-2025.06 在 A 公司实习",
                "2025.03-2025.06 在 A 公司实习");

        Result result = checker.check(resume, optimized, List.of(item));

        assertEvidenceClean(result);
        assertFalse(result.passed());
        assertTrue(hasCategory(result, Category.COVERAGE_MOVE_UNSUPPORTED)
                        || hasCategory(result, Category.COVERAGE_NEW_FACT)
                        || hasCategory(result, Category.COVERAGE_DELETED_FACT)
                        || hasCategory(result, Category.DATE_EVENT_SECTION_MISMATCH),
                () -> result.issues().toString());
    }

    // ---------------------------------------------------------------- 辅助

    private static void assertEvidenceClean(Result result) {
        assertFalse(hasCategory(result, Category.WEAK_EVIDENCE), () -> result.issues().toString());
        assertFalse(hasCategory(result, Category.BEFORE_NOT_IN_RESUME),
                () -> result.issues().toString());
        assertFalse(hasCategory(result, Category.AFTER_NOT_IN_OPTIMIZED),
                () -> result.issues().toString());
        assertFalse(hasCategory(result, Category.CHANGES_INCOMPLETE),
                () -> result.issues().toString());
    }

    private static boolean hasCategory(Result result, Category category) {
        return result.issues().stream().anyMatch(issue -> issue.category() == category);
    }

    private static ChangeItem change(String before, String after) {
        ChangeItem item = new ChangeItem();
        item.setSection("项目经历");
        item.setType("REWRITE");
        item.setReason("对齐岗位要求");
        item.setBefore(before);
        item.setAfter(after);
        return item;
    }
}
