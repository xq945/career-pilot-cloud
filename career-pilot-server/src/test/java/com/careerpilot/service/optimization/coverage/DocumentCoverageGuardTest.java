package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.ChangeItem;

class DocumentCoverageGuardTest {

    private final DocumentCoverageGuard guard = new DocumentCoverageGuard();

    @Test
    void newProjectWithUnrelatedChange_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "技能：Java\n项目：智能求职辅助平台\n参与接口开发",
                "技能：Java\n项目：电商交易系统\n参与接口开发",
                List.of(change("技能", "技能：Java", "技能：Java")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_CONTENT_WORDING)
                        || has(audit, DocumentCoverageGuard.Code.UNCOVERED_DIFF),
                () -> audit.findings().toString());
    }

    @Test
    void newQuantifiedAchievement_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "项目：智能求职辅助平台\n负责接口开发",
                "项目：智能求职辅助平台\n负责接口开发\n接口响应时间提升 30%",
                List.of(change("项目经历", "负责接口开发", "负责接口开发\n接口响应时间提升 30%")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.FACT_CHANGED),
                () -> audit.findings().toString());
    }

    @Test
    void unrelatedChangeCannotHideFabricatedExperience_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "技能：Java\n项目：智能求职辅助平台\n参与接口开发",
                "技能：Java\n项目：智能求职辅助平台\n参与接口开发\n实习：阿里巴巴\n负责电商系统",
                List.of(change("技能", "技能：Java", "技能：Java")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_CONTENT_WORDING),
                () -> audit.findings().toString());
    }

    @Test
    void projectAAndBValueSwap_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n开发 3 个模块\n### 项目 B\n开发 5 个模块",
                "### 项目 A\n开发 5 个模块\n### 项目 B\n开发 3 个模块",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_FACT), () -> audit.findings().toString());
        assertTrue(has(audit, DocumentCoverageGuard.Code.DELETED_FACT),
                () -> audit.findings().toString());
    }

    @Test
    void crossSectionTenureMove_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "## 工作经历\n2025.03-2025.06 在 A 公司实习\n## 项目经历\n智能求职辅助平台",
                "## 工作经历\n## 项目经历\n2025.03-2025.06 在 A 公司实习\n智能求职辅助平台",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_FACT)
                        || has(audit, DocumentCoverageGuard.Code.DELETED_FACT),
                () -> audit.findings().toString());
    }

    @Test
    void formatOnlyReformatting_passes() {
        String text = "### 项目 A\n- 开发 3 个模块\n- 接口响应时间 54 毫秒";
        String formatted = "## 项目 A\n\n* 开发 3 个模块\n* 接口响应时间 54 毫秒";

        DocumentCoverageGuard.Audit audit = guard.check(text, formatted, List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void legalProjectTitleRename_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n开发 3 个模块",
                "### 项目 A（重构）\n开发 3 个模块",
                List.of(change("项目经历", "### 项目 A", "### 项目 A（重构）")));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
        assertTrue(audit.sectionMap().containsValue("项目a"), () -> audit.sectionMap().toString());
    }

    @Test
    void deletedFact_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n开发 3 个模块\n接口响应时间 54 毫秒",
                "### 项目 A\n开发 3 个模块",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.DELETED_FACT),
                () -> audit.findings().toString());
    }

    @Test
    void crossLineTitleAndBody_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n开发 3 个模块",
                "### 项目 A\n\n- 开发 3 个模块",
                List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void markdownTableToList_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "### 项目 A\n- 模块数：3\n- 响应时间：54 毫秒",
                List.of(change("项目经历",
                        "| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                        "- 模块数：3\n- 响应时间：54 毫秒")));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void emptyChangesWithNewProject_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "技能：Java\n项目：智能求职辅助平台",
                "技能：Java\n项目：智能求职辅助平台\n项目：电商交易系统",
                List.of());

        assertFalse(audit.findings().isEmpty());
    }

    @Test
    void duplicateChanges_areRejected() {
        String resume = "开发 3 个模块";
        String optimized = "开发 5 个模块";
        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of(
                change("项目经历", resume, optimized),
                change("项目经历", resume, optimized)));

        assertTrue(has(audit, DocumentCoverageGuard.Code.CHANGE_DUPLICATE),
                () -> audit.findings().toString());
    }

    @Test
    void formatOnlyNoOpChange_doesNotConsumeEvidenceButIsNotBlockedAlone() {
        // 模型常把制表符/编号差异写成 REWRITE；这类 no-op 不能作为覆盖证据，
        // 但正文事实变化由块匹配独立校验，不应单独因“多余记录”阻断。
        DocumentCoverageGuard.Audit audit = guard.check(
                "技能：Java\n开发 3 个模块",
                "技能：Java\n开发 5 个模块",
                List.of(change("项目经历", "开发 3 个模块", "开发 5 个模块"),
                        change("技能", "技能：Java", "技能：Java")));

        assertFalse(has(audit, DocumentCoverageGuard.Code.CHANGE_UNUSED),
                () -> audit.findings().toString());
    }

    @Test
    void locatableButIrrelevantChange_isRejectedWhenDocumentHasMaterialChange() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "技能：Java\n开发 3 个模块",
                "技能：Java 与 Spring\n开发 5 个模块",
                List.of(change("项目经历", "开发 3 个模块", "开发 5 个模块"),
                        change("技能", "技能：Java", "技能：Java 与 Spring")));

        // 第二条不是 no-op（新增 Spring），但也没有被任何差异消费；
        // 若它没有覆盖到目标块的差异，仍应报 UNUSED 或由块级规则阻断。
        assertFalse(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void incompleteChanges_areRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "开发 3 个模块\n接口响应时间 54 毫秒",
                "开发 5 个模块\n接口响应时间 60 毫秒",
                List.of(change("项目经历", "开发 3 个模块", "开发 5 个模块")));

        assertFalse(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void lineMergeWithDeclaredChange_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "项目 A\n开发 3 个模块\n响应时间 54 毫秒",
                "项目 A：开发 3 个模块，响应时间 54 毫秒",
                List.of(change("项目经历", "项目 A\n开发 3 个模块\n响应时间 54 毫秒",
                        "项目 A：开发 3 个模块，响应时间 54 毫秒")));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void sectionReorderWithReorderChange_passes() {
        String resume = "## 技能\nJava\n## 项目 A\n开发 3 个模块";
        String optimized = "## 项目 A\n开发 3 个模块\n## 技能\nJava";
        ChangeItem reorder = change("项目经历", "## 技能\nJava\n## 项目 A\n开发 3 个模块",
                "## 项目 A\n开发 3 个模块\n## 技能\nJava");
        reorder.setType("REORDER");

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of(reorder));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void declaredChangeWithNewContentWord_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "项目 A\n参与接口开发",
                "项目 A\n参与架构设计",
                List.of(change("项目经历", "参与接口开发", "参与架构设计")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_CONTENT_WORDING),
                () -> audit.findings().toString());
    }

    @Test
    void malformedChangedBlock_isRejected() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 | 额外 |",
                List.of());

        assertFalse(audit.findings().isEmpty());
    }

    @Test
    void duplicateSourceBullets_areAmbiguousInsteadOfArbitrarilyMatched() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n- 负责接口开发\n- 负责接口开发",
                "### 项目 A\n- 负责接口开发",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.AMBIGUOUS),
                () -> audit.findings().toString());
    }

    @Test
    void tooManyIdenticalCandidates_areAmbiguous() {
        StringBuilder resume = new StringBuilder("### 项目 A\n");
        for (int i = 0; i < 20; i++) {
            resume.append("- 负责接口开发\n");
        }

        DocumentCoverageGuard.Audit audit = guard.check(
                resume.toString(),
                "### 项目 A\n- 负责接口开发",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.AMBIGUOUS),
                () -> audit.findings().toString());
    }

    @Test
    void malformedTargetTable_isExplicitUnparsable() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 |",
                "### 项目 A\n| 模块数 | 响应时间 |\n| --- | --- |\n| 3 | 54 毫秒 | 额外 |",
                List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.UNPARSABLE),
                () -> audit.findings().toString());
    }

    @Test
    void changeDoesNotCoverOtherTextChangesInSameBlock() {
        String resume = "负责接口开发和性能测试，响应 54 毫秒";
        String optimized = "负责接口开发和性能优化，响应 60 毫秒";

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized,
                List.of(change("项目经历", "性能测试", "性能优化")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.UNCOVERED_DIFF)
                        || has(audit, DocumentCoverageGuard.Code.FACT_CHANGED),
                () -> audit.findings().toString());
    }

    @Test
    void asciiPrefixHeadingIsNotTreatedAsRename() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "### Java\n开发 1 个模块",
                "### JavaScript\n开发 1 个模块",
                List.of(change("项目经历", "### Java", "### JavaScript")));

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_SECTION)
                        || has(audit, DocumentCoverageGuard.Code.DELETED_FACT),
                () -> audit.findings().toString());
    }

    @Test
    void lineSplitWithDeclaredChange_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "负责接口开发 响应 54 毫秒",
                "- 负责接口开发\n- 响应 54 毫秒",
                List.of(change("项目经历", "负责接口开发 响应 54 毫秒",
                        "- 负责接口开发\n- 响应 54 毫秒")));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void sameSectionBulletReorderWithoutChange_passes() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "## 项目 A\n- 负责接口开发\n- 负责性能测试",
                "## 项目 A\n- 负责性能测试\n- 负责接口开发",
                List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void plainTextSourceToMarkdownHeadingTarget_passes() {
        String resume = """
                基本信息
                · 姓名：张三
                · 学历：2027届本科
                实习经历
                · 江西云牛科技（后端开发） 2026.09-至今
                1. 参与接口开发
                """;
        String optimized = """
                # 张三
                ## 基本信息
                - 姓名：张三
                - 学历：2027届本科
                ## 实习经历
                ### 江西云牛科技 | 后端开发 | 2026.09-至今
                1. 参与接口开发
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void summarySectionMadeOfOriginalFacts_passes() {
        String resume = """
                基本信息
                教育：江西师范大学 2027届本科
                项目经历
                智能求职辅助平台 2026.07-2026.10
                参与接口开发
                """;
        String optimized = """
                ## 基本信息
                - 教育：江西师范大学 2027届本科
                ## 项目经历
                ### 智能求职辅助平台 | 2026.07-2026.10
                参与接口开发
                ## 个人总结
                2027届本科在读，具备一段实习经历
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void summaryWithGreedyProperNounNoise_passes() {
        String resume = """
                项目经历
                参与接口开发
                技能：JWT 实现无状态认证
                荣誉：第十六届蓝桥杯全国软件和信息技术专业人才大赛
                """;
        String optimized = """
                ## 项目经历
                参与接口开发
                技能：JWT 实现无状态认证
                荣誉：第十六届蓝桥杯全国软件和信息技术专业人才大赛
                ## 个人总结
                涉及 JWT 无状态认证，并在蓝桥杯等编程竞赛中获奖
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void summarySectionWithNewNumber_isRejected() {
        String resume = """
                项目经历
                智能求职辅助平台
                成果：接口 54 毫秒返回
                """;
        String optimized = """
                ## 项目经历
                ### 智能求职辅助平台
                成果：接口 54 毫秒返回
                ## 个人总结
                接口响应提升 30%
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertTrue(has(audit, DocumentCoverageGuard.Code.NEW_FACT),
                () -> audit.findings().toString());
    }

    @Test
    void summarySectionWithNewProperNoun_isRejected() {
        String resume = """
                项目经历
                智能求职辅助平台
                参与接口开发
                """;
        String optimized = """
                ## 项目经历
                ### 智能求职辅助平台
                参与接口开发
                ## 个人总结
                参与阿里巴巴公司项目
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertFalse(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void educationSectionRebuiltFromBasics_passes() {
        String resume = """
                基本信息
                学校：江西师范大学
                专业：数据科学与大数据技术
                学历：2027届本科
                """;
        String optimized = """
                ## 基本信息
                - 学校：江西师范大学
                - 专业：数据科学与大数据技术
                - 学历：2027届本科
                ## 教育经历
                - 江西师范大学 | 数据科学与大数据技术 | 本科（2027届）
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void educationSectionWithNewSchool_isRejected() {
        String resume = """
                基本信息
                学校：江西师范大学
                学历：2027届本科
                """;
        String optimized = """
                ## 基本信息
                - 学校：江西师范大学
                - 学历：2027届本科
                ## 教育经历
                - 清华大学 | 本科（2027届）
                """;

        DocumentCoverageGuard.Audit audit = guard.check(resume, optimized, List.of());

        assertFalse(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void promptTagInChangeEvidenceIsStrippedBeforeLocating() {
        DocumentCoverageGuard.Audit audit = guard.check(
                "参与接口开发",
                "参与接口开发，负责测试",
                List.of(change("项目经历", "【简历原文】\n参与接口开发", "参与接口开发，负责测试")));

        assertTrue(audit.findings().isEmpty(), () -> audit.findings().toString());
    }

    @Test
    void duplicateGenericFieldLabels_doNotCauseAmbiguity() {
        String text = """
                ## 项目 A
                核心工作：
                参与接口开发
                ## 项目 B
                核心工作：
                参与测试
                """;

        DocumentCoverageGuard.Audit audit = guard.check(text, text, List.of());

        assertFalse(has(audit, DocumentCoverageGuard.Code.AMBIGUOUS),
                () -> audit.findings().toString());
    }

    private static boolean has(DocumentCoverageGuard.Audit audit,
                               DocumentCoverageGuard.Code code) {
        return audit.findings().stream().anyMatch(f -> f.code() == code);
    }

    private static ChangeItem change(String section, String before, String after) {
        ChangeItem item = new ChangeItem();
        item.setSection(section);
        item.setType("REWRITE");
        item.setReason("对齐岗位");
        item.setBefore(before);
        item.setAfter(after);
        return item;
    }
}
