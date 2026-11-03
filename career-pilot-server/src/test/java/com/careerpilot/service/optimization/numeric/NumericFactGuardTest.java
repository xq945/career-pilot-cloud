package com.careerpilot.service.optimization.numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.numeric.NumericFactGuard.Audit;
import com.careerpilot.service.optimization.numeric.NumericFactGuard.Reason;
import com.careerpilot.service.optimization.numeric.NumericFactGuard.Verdict;

class NumericFactGuardTest {

    private final NumericFactGuard guard = new NumericFactGuard();

    @Test
    void userCountMagnitudeAndPlainFormMatch() {
        Audit audit = audit("用户数 3 万，订单数 5 千。", "用户数 30000，订单数 5000。");

        assertEquals(2, audit.matched());
        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void userCountValueChangeIsRejected() {
        Audit audit = audit("用户数 3 万，订单数 5 千。", "用户数 5 万，订单数 5 千。");

        assertTrue(hasReason(audit, Reason.MISMATCH_NEW_VALUE));
        assertEquals(Verdict.MISMATCH, finding(audit, Reason.MISMATCH_NEW_VALUE).verdict());
    }

    @Test
    void conjunctionTextUnchangedPasses() {
        Audit audit = audit("负责 3 个模块和 5 个接口。", "负责 3 个模块和 5 个接口。");

        assertEquals(2, audit.matched());
        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void conjunctionObjectSwapIsRejected() {
        Audit audit = audit("负责 3 个模块和 5 个接口。", "负责 5 个模块和 3 个接口。");

        assertEquals(0, audit.matched());
        assertEquals(2, audit.mismatched());
        assertTrue(hasReason(audit, Reason.MISMATCH_OBJECT));
        assertFalse(hasReason(audit, Reason.MISMATCH_NEW_VALUE),
                "3/5 都在原文中出现，不能误报为新增数字");

        Audit comma = audit("负责 3 个模块，5 个接口。", "负责 5 个模块，3 个接口。");
        assertTrue(hasReason(comma, Reason.MISMATCH_OBJECT));
        assertFalse(hasReason(comma, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void multipleRateMetricsAreAssociatedByClause() {
        Audit swap = audit("提升转化率 30%，降低成本 20%。",
                "提升转化率 20%，降低成本 30%。");
        assertTrue(hasReason(swap, Reason.MISMATCH_METRIC));

        Audit counts = audit("用户数 3 万，订单数 5 千。",
                "用户数 5 千，订单数 3 万。");
        assertTrue(hasReason(counts, Reason.MISMATCH_OBJECT)
                || hasReason(counts, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void responseTimeAliasPassesButDifferentMetricDoesNot() {
        Audit alias = audit("接口响应时间 5 秒。", "接口响应耗时 5 秒。");
        assertEquals(1, alias.matched());
        assertTrue(alias.findings().isEmpty(), () -> alias.findings().toString());

        Audit metric = audit("转化率提升 30%。", "成本降低 30%。");
        assertTrue(hasReason(metric, Reason.MISMATCH_METRIC));
    }

    @Test
    void unitAndContextSwapsAreRejectedWithoutNewNumberNoise() {
        Audit unitSwap = audit("负责 3 个模块，接口平均响应时间为 5 秒。",
                "负责 5 个模块，接口平均响应时间为 3 秒。");
        assertTrue(hasReason(unitSwap, Reason.MISMATCH_UNIT));
        assertFalse(hasReason(unitSwap, Reason.MISMATCH_NEW_VALUE));

        Audit unitChange = audit("接口响应时间为 5 秒。", "接口响应时间为 5 毫秒。");
        assertTrue(hasReason(unitChange, Reason.MISMATCH_UNIT));
    }

    @Test
    void dateAndVersionFormatsPassAndValueChangesFail() {
        Audit date = audit("2025 年 3 月入职，负责 3 个模块。",
                "2025年3月入职，负责 3 个模块。");
        assertEquals(2, date.matched());
        assertTrue(date.findings().isEmpty(), () -> date.findings().toString());

        Audit dateChange = audit("2025 年 3 月入职，负责 3 个模块。",
                "2025年4月入职，负责 3 个模块。");
        assertTrue(hasReason(dateChange, Reason.MISMATCH_NEW_VALUE));

        Audit version = audit("技术栈：Java 17、Spring Boot 3.5。",
                "技术栈：Java 17、SpringBoot 3.5。");
        assertEquals(2, version.matched());
        assertTrue(version.findings().isEmpty(), () -> version.findings().toString());

        Audit versionChange = audit("技术栈：Java 17、Spring Boot 3.5。",
                "技术栈：Java 21、Spring Boot 3.5。");
        assertTrue(hasReason(versionChange, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void numericTypesNormalizeAndChangesAreRejected() {
        Audit negative = audit("利润率 -5%，主要受促销影响。", "利润率 5%，主要受促销影响。");
        assertTrue(hasReason(negative, Reason.MISMATCH_NEW_VALUE));

        Audit decimal = audit("工作 1.5 年，负责接口开发。", "工作 1.50 年，负责接口开发。");
        assertTrue(decimal.findings().isEmpty(), () -> decimal.findings().toString());

        Audit percent = audit("核心指标提升 30%，达到预期。", "核心指标提升 30％，达到预期。");
        assertTrue(percent.findings().isEmpty(), () -> percent.findings().toString());

        Audit thousands = audit("系统累计处理 1,000 条数据，运行稳定。",
                "系统累计处理 1000 条数据，运行稳定。");
        assertTrue(thousands.findings().isEmpty(), () -> thousands.findings().toString());
    }

    @Test
    void duplicateNumbersUseContextAndCapacity() {
        Audit identical = audit("模块数 3 个。接口数 3 个。", "模块数 3 个。接口数 3 个。");
        assertEquals(2, identical.matched());
        assertTrue(identical.findings().isEmpty(), () -> identical.findings().toString());

        Audit extra = audit("模块数 3 个。", "模块数 3 个。模块数 3 个。");
        assertTrue(hasReason(extra, Reason.MISMATCH_OCCURRENCE));

        Audit removed = audit("模块数 3 个。模块数 3 个。", "模块数 3 个。");
        assertTrue(removed.findings().isEmpty(), () -> removed.findings().toString());

        Audit differentContext = audit("模块数 3 个。接口数 3 个。", "项目数 3 个。");
        assertTrue(hasReason(differentContext, Reason.MISMATCH_OBJECT),
                "不能在两个不同上下文的同值数字中随意选一个");
    }

    @Test
    void bareNumbersOnlyPassWhenLocalLineIsUnchanged() {
        Audit unchanged = audit("3 个模块，5 秒，30%。", "3 个模块，5 秒，30%。");
        assertEquals(3, unchanged.matched());
        assertTrue(unchanged.findings().isEmpty(), () -> unchanged.findings().toString());

        Audit changedContext = audit("本季度整体达成 30%。", "本季度成本降低 30%。");
        assertTrue(hasReason(changedContext, Reason.INDETERMINATE_ONE_SIDED_CONTEXT));
    }

    @Test
    void unsupportedExpressionOnlyPassesWhenUnchanged() {
        Audit unchanged = audit("负责若干模块。", "负责若干模块。");
        assertEquals(1, unchanged.matched());

        Audit changed = audit("负责若干模块。", "负责 3 个模块。");
        assertTrue(hasReason(changed, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void controlledTimeClarificationPasses() {
        Audit audit = audit("成果：接口 54 毫秒返回", "成果：接口响应耗时 54 毫秒");
        assertEquals(1, audit.matched());
        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());

        Audit unknown = audit("接口响应 5 秒。", "接口编译时间 5 秒。");
        assertTrue(hasReason(unknown, Reason.INDETERMINATE_ONE_SIDED_CONTEXT));
    }

    @Test
    void qualifierChangeIsRejected() {
        Audit audit = audit("本季度指标提升约 30%。", "本季度指标提升 30%。");
        assertTrue(hasReason(audit, Reason.MISMATCH_QUALIFIER));
    }

    // ---------------------------------------------------------------- Repair B：P0 反例

    @Test
    void dataObjectChangeWithSameNumberIsRejected() {
        Audit audit = audit("处理了 3 个数据", "处理了 3 个数据库");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertFalse(audit.findings().isEmpty());
        assertFalse(hasReason(audit, Reason.MISMATCH_NEW_VALUE),
                "3 在两个文本中都存在，不能误报为新增数字");
    }

    @Test
    void crossLineProjectValueSwapIsRejected() {
        Audit audit = audit("项目 A：开发 3 个模块\n项目 B：开发 5 个模块",
                "项目 A：开发 5 个模块\n项目 B：开发 3 个模块");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertTrue(hasReason(audit, Reason.MISMATCH_CONTEXT));
        assertFalse(hasReason(audit, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void markdownHeadingProjectValueSwapIsRejected() {
        Audit audit = audit("### 项目 A\n开发 3 个模块\n### 项目 B\n开发 5 个模块",
                "### 项目 A\n开发 5 个模块\n### 项目 B\n开发 3 个模块");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertTrue(hasReason(audit, Reason.MISMATCH_CONTEXT));
    }

    @Test
    void adjacentProjectsWithSameMetricValueSwapIsRejected() {
        Audit audit = audit("项目 A 模块数 3 个\n项目 B 模块数 5 个",
                "项目 A 模块数 5 个\n项目 B 模块数 3 个");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertTrue(hasReason(audit, Reason.MISMATCH_CONTEXT));
    }

    @Test
    void tableHeaderReorderWithUnchangedDataRowIsRejected() {
        Audit audit = audit("| 模块数 | 接口数 |\n| --- | --- |\n| 3 | 5 |",
                "| 接口数 | 模块数 |\n| --- | --- |\n| 3 | 5 |");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertTrue(hasReason(audit, Reason.MISMATCH_CONTEXT));
        assertFalse(hasReason(audit, Reason.MISMATCH_NEW_VALUE));
    }

    @Test
    void mergedLinesThatDropProjectAssociationAreRejected() {
        Audit audit = audit("项目 A：开发 3 个模块\n项目 B：开发 5 个模块",
                "项目 A：开发 3 个模块，开发 5 个模块");

        // 3 仍属于项目 A，可以匹配；5 失去项目 B 标签后不能沿用项目 A
        assertEquals(1, audit.matched(), () -> audit.findings().toString());
        assertEquals(1, audit.mismatched(), () -> audit.findings().toString());
        assertTrue(hasReason(audit, Reason.MISMATCH_CONTEXT),
                "第二个数字失去项目 B 标签后不能沿用项目 A");
    }

    @Test
    void ambiguousChineseNumeralIsNotEquatedWithArabicValue() {
        Audit audit = audit("处理约三千五条记录", "处理约 3005 条记录");

        assertEquals(0, audit.matched(), () -> audit.findings().toString());
        assertFalse(audit.findings().isEmpty(),
                "三千五 语义不唯一，不能当成 3005 放行");
    }

    @Test
    void ambiguousChineseNumeralUnchangedStillPasses() {
        Audit audit = audit("处理约三千五条记录", "处理约 三千五 条记录");

        assertEquals(1, audit.matched(), () -> audit.findings().toString());
        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void chineseMagnitudeEquivalencesPass() {
        Audit magnitudes = audit("用户数 一点五万，订单数 三万。",
                "用户数 15000，订单数 30000。");
        assertEquals(2, magnitudes.matched(), () -> magnitudes.findings().toString());
        assertTrue(magnitudes.findings().isEmpty(), () -> magnitudes.findings().toString());

        Audit alternate = audit("用户数 一万五千，订单数 三十万。",
                "用户数 1.5 万，订单数 300000。");
        assertEquals(2, alternate.matched(), () -> alternate.findings().toString());
        assertTrue(alternate.findings().isEmpty(), () -> alternate.findings().toString());
    }

    @Test
    void explicitChineseFormsPassAndAmbiguousFormIsRejected() {
        assertTrue(audit("用户数 三千零五", "用户数 3005").findings().isEmpty());
        assertTrue(audit("用户数 三千五百", "用户数 3500").findings().isEmpty());

        Audit colloquial = audit("用户数 三千五", "用户数 3500");
        assertEquals(0, colloquial.matched(), () -> colloquial.findings().toString());
        assertFalse(colloquial.findings().isEmpty());

        Audit digitByDigit = audit("用户数 三千五", "用户数 3005");
        assertEquals(0, digitByDigit.matched(), () -> digitByDigit.findings().toString());
        assertFalse(digitByDigit.findings().isEmpty());
    }

    @Test
    void approximationWordsAreNotSilentlyDropped() {
        assertTrue(hasReason(audit("用户数 三千多", "用户数 3000"),
                Reason.MISMATCH_QUALIFIER));
        assertTrue(hasReason(audit("用户数 三千余", "用户数 3000"),
                Reason.MISMATCH_QUALIFIER));
        assertTrue(hasReason(audit("用户数 约三千", "用户数 3000"),
                Reason.MISMATCH_QUALIFIER));
        assertTrue(hasReason(audit("用户数 三千左右", "用户数 3000"),
                Reason.MISMATCH_QUALIFIER));

        Audit nearAmbiguous = audit("用户数 近三千五", "用户数 3500");
        assertEquals(0, nearAmbiguous.matched(), () -> nearAmbiguous.findings().toString());
        assertFalse(nearAmbiguous.findings().isEmpty());

        Audit bothApproximate = audit("用户数 三千多", "用户数 3000 多");
        assertTrue(bothApproximate.findings().isEmpty(),
                () -> bothApproximate.findings().toString());
    }

    @Test
    void rangeAndKSuffixAreConservativelyRejected() {
        Audit range = audit("预计三千至四千人", "预计 3000 人");
        assertEquals(0, range.matched(), () -> range.findings().toString());
        assertFalse(range.findings().isEmpty());

        Audit k = audit("处理 3.5k 条数据", "处理 3500 条数据");
        assertEquals(0, k.matched(), () -> k.findings().toString());
        assertFalse(k.findings().isEmpty());

        Audit unchangedRange = audit("预计三千至四千人", "预计三千至四千人");
        assertTrue(unchangedRange.findings().isEmpty(),
                () -> unchangedRange.findings().toString());
        Audit unchangedK = audit("处理 3.5k 条数据", "处理 3.5k 条数据");
        assertTrue(unchangedK.findings().isEmpty(), () -> unchangedK.findings().toString());
    }

    @Test
    void identicalStructuredDocumentsStillPass() {
        String headings = "### 项目 A\n开发 3 个模块\n### 项目 B\n开发 5 个模块";
        Audit headingAudit = audit(headings, headings);
        assertEquals(2, headingAudit.matched(), () -> headingAudit.findings().toString());
        assertTrue(headingAudit.findings().isEmpty(), () -> headingAudit.findings().toString());

        String table = "| 模块数 | 接口数 |\n| --- | --- |\n| 3 | 5 |";
        Audit tableAudit = audit(table, table);
        assertEquals(2, tableAudit.matched(), () -> tableAudit.findings().toString());
        assertTrue(tableAudit.findings().isEmpty(), () -> tableAudit.findings().toString());
    }

    @Test
    void costRateIsNotTruncatedToCost() {
        Audit audit = audit("本季度 30% 成本率", "本季度 30% 成本");

        assertTrue(hasReason(audit, Reason.MISMATCH_METRIC),
                () -> audit.findings().toString());
    }

    @Test
    void headingDateFormatChangeDoesNotBlockUnchangedFacts() {
        String resume = "### 项目 A | 2026.07-2026.10\n开发 3 个模块";
        String optimized = "### 项目 A | 2026年7月-2026年10月\n开发 3 个模块";

        Audit audit = audit(resume, optimized);

        assertEquals(2, audit.matched(), () -> audit.findings().toString());
        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void changeSkeletonStillReportsChangeIndex() {
        String resume = "项目 A：开发 3 个模块\n项目 B：开发 5 个模块";
        ChangeItem item = new ChangeItem();
        item.setBefore("开发 3 个模块");
        item.setAfter("开发 5 个模块");

        Audit audit = guard.check(resume, resume, List.of(item));

        assertEquals(2, audit.matched(), () -> audit.findings().toString());
        assertEquals(1, audit.mismatched(), () -> audit.findings().toString());
        assertEquals(Integer.valueOf(0), audit.findings().get(0).changeIndex());
    }

    private Audit audit(String resume, String optimized) {
        return guard.check(resume, optimized, List.of());
    }

    private static boolean hasReason(Audit audit, Reason reason) {
        return audit.findings().stream().anyMatch(f -> f.reason() == reason);
    }

    private static NumericFactGuard.Finding finding(Audit audit, Reason reason) {
        return audit.findings().stream().filter(f -> f.reason() == reason).findFirst().orElseThrow();
    }
}
