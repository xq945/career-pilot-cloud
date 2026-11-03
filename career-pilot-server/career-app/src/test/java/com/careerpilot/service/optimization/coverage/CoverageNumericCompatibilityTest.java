package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.numeric.NumericFactGuard;

/**
 * 覆盖守卫 × 数字守卫（修复 B）的衔接回归。
 *
 * <p>覆盖守卫只把“已证明事实等价”的块区间交给数字守卫跳过；未被证明的区间
 * 仍必须由数字守卫按原三态语义检查。这些用例直接组合两个纯函数组件，
 * 不经过 Spring、数据库或 Redis。
 */
class CoverageNumericCompatibilityTest {

    private final DocumentCoverageGuard coverage = new DocumentCoverageGuard();

    private final NumericFactGuard numeric = new NumericFactGuard();

    @Test
    void tableToListConversion_isEquivalentAndIgnoredByNumericGuard() {
        String resume = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |";
        String optimized = "### 项目 A\n- 模块数：3\n- 响应时间：54 毫秒";
        List<ChangeItem> changes = List.of(change(
                "| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "- 模块数：3\n- 响应时间：54 毫秒"));

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, changes);
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, changes,
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertTrue(coverageAudit.findings().isEmpty(),
                () -> coverageAudit.findings().toString());
        assertTrue(numericAudit.findings().isEmpty(), () -> numericAudit.findings().toString());
    }

    @Test
    void tableToListWithChangedNumber_isRejectedAndNotIgnored() {
        String resume = "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |";
        String optimized = "### 项目 A\n- 模块数：5\n- 响应时间：54 毫秒";
        List<ChangeItem> changes = List.of(change(
                "| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "- 模块数：5\n- 响应时间：54 毫秒"));

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, changes);
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, changes,
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertFalse(coverageAudit.findings().isEmpty());
        assertFalse(numericAudit.findings().isEmpty(),
                "未被覆盖守卫证明等价的数字必须继续由数字守卫检查");
    }

    @Test
    void lineMerge_isEquivalentAndIgnored() {
        String resume = "项目 A\n开发 3 个模块\n响应时间 54 毫秒";
        String optimized = "项目 A：开发 3 个模块，响应时间 54 毫秒";
        List<ChangeItem> changes = List.of(change(
                "项目 A\n开发 3 个模块\n响应时间 54 毫秒",
                "项目 A：开发 3 个模块，响应时间 54 毫秒"));

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, changes);
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, changes,
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertTrue(coverageAudit.findings().isEmpty(),
                () -> coverageAudit.findings().toString());
        assertTrue(numericAudit.findings().isEmpty(), () -> numericAudit.findings().toString());
    }

    @Test
    void lineSplit_isEquivalentAndIgnored() {
        String resume = "负责接口开发 响应 54 毫秒";
        String optimized = "- 负责接口开发\n- 响应 54 毫秒";
        List<ChangeItem> changes = List.of(change("负责接口开发 响应 54 毫秒",
                "- 负责接口开发\n- 响应 54 毫秒"));

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, changes);
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, changes,
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertTrue(coverageAudit.findings().isEmpty(),
                () -> coverageAudit.findings().toString());
        assertTrue(numericAudit.findings().isEmpty(), () -> numericAudit.findings().toString());
    }

    @Test
    void headingLevelAndListStyleChange_doesNotBreakNumberAssociation() {
        String resume = "### 项目 A\n- 开发 3 个模块";
        String optimized = "## 项目 A\n* 开发 3 个模块";

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, List.of());
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, List.of(),
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertTrue(coverageAudit.findings().isEmpty(),
                () -> coverageAudit.findings().toString());
        assertTrue(numericAudit.findings().isEmpty(), () -> numericAudit.findings().toString());
    }

    @Test
    void sectionReorder_doesNotBreakNumberAssociation() {
        String resume = "## 项目 A\n开发 3 个模块\n## 项目 B\n开发 5 个模块";
        String optimized = "## 项目 B\n开发 5 个模块\n## 项目 A\n开发 3 个模块";

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, List.of());
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, List.of(),
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertTrue(coverageAudit.findings().isEmpty(),
                () -> coverageAudit.findings().toString());
        assertTrue(numericAudit.findings().isEmpty(), () -> numericAudit.findings().toString());
    }

    @Test
    void uncoveredBlockNumberChange_isStillRejectedByNumericGuard() {
        String resume = "第一行 负责接口开发，共 3 个模块\n第二行 响应 54 毫秒";
        String optimized = "第一行 负责接口优化，共 3 个模块\n第二行 响应 60 毫秒";
        List<ChangeItem> changes = List.of(change("负责接口开发", "负责接口优化"));

        DocumentCoverageGuard.Audit coverageAudit = coverage.check(resume, optimized, changes);
        NumericFactGuard.Audit numericAudit = numeric.check(resume, optimized, changes,
                coverageAudit.ignoredResume(), coverageAudit.ignoredOptimized());

        assertFalse(coverageAudit.findings().isEmpty(),
                "第二行 54→60 未被 change 覆盖，覆盖守卫必须阻断");
        assertFalse(numericAudit.findings().isEmpty(),
                "未被覆盖守卫忽略的第二行仍由数字守卫检查");
    }

    @Test
    void emptyIgnoreRangesPreserveOriginalNumericSemantics() {
        String resume = "负责 3 个模块，响应 5 秒";
        String optimized = "负责 5 个模块，响应 3 秒";
        List<ChangeItem> changes = List.of(change(resume, optimized));

        NumericFactGuard.Audit original = numeric.check(resume, optimized, changes);
        NumericFactGuard.Audit emptyRanges = numeric.check(resume, optimized, changes,
                List.of(), List.of());

        assertEquals(original.findings(), emptyRanges.findings());
        assertFalse(original.findings().isEmpty());
    }

    private static ChangeItem change(String before, String after) {
        ChangeItem item = new ChangeItem();
        item.setSection("项目经历");
        item.setType("REWRITE");
        item.setReason("对齐岗位");
        item.setBefore(before);
        item.setAfter(after);
        return item;
    }
}
