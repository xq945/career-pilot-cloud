package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DateEventRoleGuardTest {

    private final DateEventRoleGuard guard = new DateEventRoleGuard();

    // ---------------------------------------------------------------- D-01..D-10

    @Test
    void employmentStartToEnd_isRejected() {
        assertCode("2025年3月入职", "2025年3月离职", DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void projectStartToEnd_isRejected() {
        assertCode("2025年3月开始项目", "2025年3月结束项目",
                DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void graduationToEnrollment_isRejected() {
        assertCode("2025年3月毕业", "2025年3月入学", DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void reversedDateRange_isRejected() {
        assertCode("2025年3月至2025年6月实习", "2025年6月至2025年3月实习",
                DateEventRoleGuard.Code.RANGE_ORDER_INVALID);
    }

    @Test
    void awardToApplication_isRejected() {
        assertCode("2025年3月获得证书", "2025年3月报名考试",
                DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void sameRoleAlias_passes() {
        assertPass("2025年3月入职", "2025年3月加入团队");
    }

    @Test
    void oneSidedRole_isRejected() {
        assertCode("2025年3月", "2025年3月入职", DateEventRoleGuard.Code.CONTEXT_AMBIGUOUS);
    }

    @Test
    void differentEventSubject_isRejected() {
        assertCode("2025年3月入职 A 公司", "2025年3月入职 B 公司",
                DateEventRoleGuard.Code.SUBJECT_MISMATCH);
    }

    @Test
    void crossSectionDateMove_isRejected() {
        assertCode("## 工作经历\n2025年3月入职 A 公司",
                "## 项目经历\n2025年3月入职 A 公司",
                DateEventRoleGuard.Code.SECTION_MISMATCH);
    }

    @Test
    void dateOnlyUnchanged_passes() {
        assertPass("2025年3月", "2025年3月");
    }

    // ---------------------------------------------------------------- 角色边界

    @Test
    void internshipSubjectChange_isRejected() {
        assertCode("2025年3月实习于 A 公司", "2025年3月实习于 B 公司",
                DateEventRoleGuard.Code.SUBJECT_MISMATCH);
    }

    @Test
    void internshipRoleIsRecognizedAndConsistent() {
        assertPass("2025年3月实习于 A 公司", "2025年3月实习于 A 公司");
    }

    @Test
    void englishJoinedToLeft_isRejected() {
        assertCode("2025.03 joined A Company", "2025.03 left A Company",
                DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void bilingualSameRole_passes() {
        assertPass("2025.03 joined", "2025年3月加入");
    }

    @Test
    void unknownEnglishVerb_isNotGuessed() {
        assertCode("2025.03 started", "2025.03 commenced",
                DateEventRoleGuard.Code.CONTEXT_AMBIGUOUS);
    }

    @Test
    void unknownChineseVerb_isNotMappedToKnownRole() {
        assertCode("2025年3月调岗", "2025年3月入职",
                DateEventRoleGuard.Code.CONTEXT_AMBIGUOUS);
    }

    @Test
    void roleInDifferentSentence_isNotBoundToDate() {
        // 角色在下一句：日期本身没有可识别事件，不跨句猜测 → 两侧都 UNKNOWN，允许通过。
        assertPass("2025年3月。入职", "2025年3月。离职");
    }

    @Test
    void roleAcrossParagraphBlock_isNotBound() {
        assertPass("2025年3月\n入职", "2025年3月\n离职");
    }

    @Test
    void roleBeyondDistanceLimit_isUnknown() {
        String filler = "甲".repeat(30);
        assertPass("2025年3月" + filler + "入职", "2025年3月" + filler + "离职");
    }

    @Test
    void multipleDatesInSameClause_areBoundByDistance() {
        assertCode("2025年3月入职，2025年6月离职",
                "2025年3月离职，2025年6月入职",
                DateEventRoleGuard.Code.ROLE_MISMATCH);
    }

    @Test
    void multipleDatesInSameClause_unchangedPasses() {
        assertPass("2025年3月入职，2025年6月离职",
                "2025年3月入职，2025年6月离职");
    }

    @Test
    void rangeAlreadyReversedInSource_passesWhenUnchanged() {
        assertPass("2025年6月至2025年3月实习", "2025年6月至2025年3月实习");
    }

    @Test
    void oneSidedSubject_isRejected() {
        assertCode("2025年3月入职 A 公司", "2025年3月入职",
                DateEventRoleGuard.Code.CONTEXT_AMBIGUOUS);
    }

    @Test
    void differentYearRangeOrder_isRejected() {
        assertCode("2024年9月至2025年1月实习", "2025年1月至2024年9月实习",
                DateEventRoleGuard.Code.RANGE_ORDER_INVALID);
    }

    private void assertCode(String resume, String optimized, DateEventRoleGuard.Code code) {
        List<DateEventRoleGuard.Finding> findings = findings(resume, optimized);
        assertTrue(findings.stream().anyMatch(f -> f.code() == code),
                () -> findings.toString());
    }

    private void assertPass(String resume, String optimized) {
        List<DateEventRoleGuard.Finding> findings = findings(resume, optimized);
        assertTrue(findings.isEmpty(), () -> findings.toString());
    }

    private List<DateEventRoleGuard.Finding> findings(String resume, String optimized) {
        return guard.check(resume, optimized, Map.of()).findings();
    }
}
