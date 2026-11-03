package com.careerpilot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.careerpilot.config.MatchScoringProperties;
import com.careerpilot.dto.MatchDimension;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.ai.EducationItem;
import com.careerpilot.dto.ai.ExperienceItem;
import com.careerpilot.dto.ai.ProjectItem;
import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.service.SkillTermMatcher.Evidence;
import com.careerpilot.service.SkillTermMatcher.Hit;
import com.careerpilot.service.SkillTermMatcher.ResumeTerms;
import com.careerpilot.service.SkillTermMatcher.TermMatch;

/**
 * 确定性评分服务（《数据库设计》6、《系统架构设计》6.2）：**纯函数，无数据库、无模型依赖**。
 *
 * <p>「模型只解释、不得覆盖总分」的实现基础：本服务的输出中总分只由规则与权重计算，
 * 模型解释链路的输出类型刻意不含任何分数字段（见《API接口设计》6.1 的字段说明）。
 *
 * <p>两条边界规则（《数据库设计》6.5）：
 * <ul>
 *   <li>岗位某维度无要求 → 记 100 分（不重归一化，保证总分可用界面标注的权重手算复核）；</li>
 *   <li>有要求但简历无证据 → 记 0 分，同时向证据账本写入含「简历未体现」的受控文案。</li>
 * </ul>
 *
 * <p>证据账本还承担"可追溯性"举证：每条记录包含「术语 → 档位 → 来源」三要素，
 * 单元测试直接断言账本而非分数（见《数据库设计》6.6）。
 */
@Service
public class MatchScoringService {

    private static final Logger log = LoggerFactory.getLogger(MatchScoringService.class);

    /** 分数上限（《数据库设计》5.6：服务层强制限制在 0–100） */
    private static final BigDecimal MAX_SCORE = new BigDecimal("100.00");

    /** 「无要求」的表述（《数据库设计》6.5：岗位未提出该要求） */
    private static final Pattern NO_REQUIREMENT = Pattern.compile("不限|无要求|无特殊要求");

    // ---------------------------------------------------------------- 维度公式的解析正则

    /** 年限区间（取数字下限）："1-3年" → 1 */
    private static final Pattern YEAR_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-—~～至]\\s*(\\d+(?:\\.\\d+)?)\\s*年");

    /** 单一年限："3年以上" → 3 */
    private static final Pattern YEAR_SINGLE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*年");

    /** 中文数字年限："三年以上" → 3（只支持单个汉字，覆盖常见写法） */
    private static final Pattern YEAR_CHINESE = Pattern.compile("([一二两三四五六七八九十])\\s*年");

    private static final Map<String, Integer> CHINESE_NUMBERS = Map.ofEntries(
            Map.entry("一", 1), Map.entry("两", 2), Map.entry("二", 2), Map.entry("三", 3),
            Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7),
            Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));

    /** 经历区间中的年月：yyyy.mm / yyyy-mm / yyyy/mm / yyyy年m月（月后不能紧跟数字，避免 2024-2025 误读） */
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})\\s*[.\\-/年]\\s*(\\d{1,2})(?!\\d)");

    /** 区间中的纯年份（月按 1 计） */
    private static final Pattern YEAR_ONLY = Pattern.compile("(\\d{4})");

    /** 「至今」等表述：计算累计月数时替换为当前年月（不用单字「今」，避免误伤「今年」等写法） */
    private static final Pattern PRESENT = Pattern.compile("至今|现在|迄今");

    private final MatchScoringProperties properties;

    public MatchScoringService(MatchScoringProperties properties) {
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 按《数据库设计》6 的规则计算五个维度分与总分。
     *
     * @param job    岗位分析（已通过结构校验与过期判定）
     * @param resume 简历分析（已通过结构校验）
     */
    public MatchScoringResult score(JobAnalysis job, ResumeAnalysis resume) {
        ResumeTerms full = fullTerms(resume);
        ResumeTerms projectOnly = projectTerms(resume);

        List<ScoringEvidence> evidences = new ArrayList<>();
        Map<MatchDimension, Integer> scores = new EnumMap<>(MatchDimension.class);
        scores.put(MatchDimension.REQUIRED_SKILLS,
                scoreTerms(job.getRequiredSkills(), MatchDimension.REQUIRED_SKILLS, full, evidences));
        scores.put(MatchDimension.PROJECT_RELEVANCE,
                scoreTerms(projectRequirements(job), MatchDimension.PROJECT_RELEVANCE, projectOnly, evidences));
        scores.put(MatchDimension.EXPERIENCE,
                scoreExperience(job.getExperienceRequirement(), resume, evidences));
        scores.put(MatchDimension.EDUCATION,
                scoreEducation(job.getEducationRequirement(), resume, evidences));
        scores.put(MatchDimension.PREFERRED_SKILLS,
                scoreTerms(job.getPreferredSkills(), MatchDimension.PREFERRED_SKILLS, full, evidences));

        warnIfNoRequirement(job);

        // 维度分按权重降序输出（《数据库设计》6.1 的顺序约定），总分 = Σ 权重 × 维度分 / 100
        List<MatchDimensionScore> items = new ArrayList<>();
        long weightedSum = 0;
        for (MatchDimension dimension : properties.orderByWeightDesc()) {
            int weight = properties.weightOf(dimension);
            int score = scores.get(dimension);
            items.add(new MatchDimensionScore(dimension.getKey(), dimension.getLabel(), weight, score));
            weightedSum += (long) weight * score;
        }
        BigDecimal total = BigDecimal.valueOf(weightedSum)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        return new MatchScoringResult(clamp(total),
                new MatchDimensionScores(properties.ruleVersion(), items), evidences);
    }

    // ---------------------------------------------------------------- 技能类维度（1 / 2 / 5）

    /**
     * 技能类维度的统一定分：逐条按 {@link SkillTermMatcher} 判定，
     * 维度分 = round(100 × Σs / N)。
     * 岗位未提取到该维度要求时记 100 分（《数据库设计》6.5）。
     */
    private static int scoreTerms(List<String> requirements, MatchDimension dimension,
                                  ResumeTerms terms, List<ScoringEvidence> evidences) {
        List<String> valid = nonBlank(requirements);
        if (valid.isEmpty()) {
            return 100;
        }
        double sum = 0;
        for (String requirement : valid) {
            TermMatch match = SkillTermMatcher.match(requirement, terms);
            sum += match.hit().score();
            evidences.add(new ScoringEvidence(dimension, requirement.strip(), statusOf(match.hit()),
                    match.source()));
        }
        return (int) Math.round(100.0 * sum / valid.size());
    }

    /** 项目相关性维度的岗位侧要求：requiredSkills ∪ technologyStack 去重（《数据库设计》6.4） */
    private static List<String> projectRequirements(JobAnalysis job) {
        Set<String> merged = new LinkedHashSet<>();
        addAll(merged, job.getRequiredSkills());
        addAll(merged, job.getTechnologyStack());
        return List.copyOf(merged);
    }

    private static ScoringEvidence.Status statusOf(Hit hit) {
        return switch (hit) {
            case DIRECT -> ScoringEvidence.Status.MATCHED;
            case PARTIAL -> ScoringEvidence.Status.PARTIAL;
            case MISS -> ScoringEvidence.Status.MISSING;
        };
    }

    // ---------------------------------------------------------------- 经历维度（3）

    /**
     * 工作/实习经历（《数据库设计》6.4）：要求年限按月数比例计分；
     * 要求无数字时回退存在性规则；有要求而完全无条目时记 0 分并写入「未体现」文案。
     *
     * <p>本维度不产出 MATCHED/PARTIAL 档的账本——部分满足的语义由分数与模型解释承载，
     * 账本补全只针对"缺口"与技能类的部分证据。
     */
    private static int scoreExperience(String requirement, ResumeAnalysis resume,
                                       List<ScoringEvidence> evidences) {
        List<ExperienceItem> items = nullSafe(resume.getExperience());
        if (isNoRequirement(requirement)) {
            return 100;
        }
        int requiredMonths = parseRequiredMonths(requirement);
        if (requiredMonths <= 0) {
            return scoreByPresence(items, evidences);
        }
        if (items.isEmpty()) {
            evidences.add(new ScoringEvidence(MatchDimension.EXPERIENCE, "工作/实习经历",
                    ScoringEvidence.Status.MISSING, null));
            return 0;
        }
        int actualMonths = totalMonths(items);
        if (actualMonths < 0) {
            log.warn("简历经历的 period 全部无法解析，回退存在性规则");
            return scoreByPresence(items, evidences);
        }
        return Math.min(100, (int) Math.round(100.0 * actualMonths / requiredMonths));
    }

    private static int scoreByPresence(List<ExperienceItem> items, List<ScoringEvidence> evidences) {
        if (items.isEmpty()) {
            evidences.add(new ScoringEvidence(MatchDimension.EXPERIENCE, "工作/实习经历",
                    ScoringEvidence.Status.MISSING, null));
            return 0;
        }
        return 100;
    }

    /** 要求年限 → 月数；无数字（如「有实习经历优先」）返回 -1 */
    private static int parseRequiredMonths(String requirement) {
        Matcher range = YEAR_RANGE.matcher(requirement);
        if (range.find()) {
            return monthsOfYears(range.group(1));
        }
        Matcher single = YEAR_SINGLE.matcher(requirement);
        if (single.find()) {
            return monthsOfYears(single.group(1));
        }
        Matcher chinese = YEAR_CHINESE.matcher(requirement);
        if (chinese.find()) {
            Integer years = CHINESE_NUMBERS.get(chinese.group(1));
            return years == null ? -1 : years * 12;
        }
        return -1;
    }

    private static int monthsOfYears(String years) {
        try {
            return (int) Math.round(Double.parseDouble(years) * 12);
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 各段经历累加的月数；全部不可解析返回 -1（由调用方回退存在性规则并记 WARN） */
    private static int totalMonths(List<ExperienceItem> items) {
        int total = 0;
        boolean any = false;
        for (ExperienceItem item : items) {
            int months = monthsOf(item.getPeriod());
            if (months >= 0) {
                total += months;
                any = true;
            }
        }
        return any ? total : -1;
    }

    /**
     * 单段经历的时间区间 → 月数；无法解析返回 -1。
     * 支持 {@code 2024.07-2024.09}、{@code 2024.7—2024.9}、{@code 2023年6月-2024年8月}、
     * {@code 2024.07-至今}、{@code 2024-2025}（只写年份时月按 1 计）；多个区间成对累加。
     */
    private static int monthsOf(String period) {
        if (period == null || period.isBlank()) {
            return -1;
        }
        LocalDate now = LocalDate.now();
        String text = period.replace('—', '-').replace('－', '-')
                .replace('~', '-').replace('～', '-');
        text = PRESENT.matcher(text).replaceAll(now.getYear() + "." + now.getMonthValue());

        // 年月点按下标收集，保证与原文顺序一致；已识别的年月区间从剩余文本中挖掉，
        // 避免 "2024-2025" 的年份被 YEAR_MONTH 误读为 "2024-20"（由 (?!\d) 前瞻保证）
        List<int[]> points = new ArrayList<>();
        StringBuilder rest = new StringBuilder(text);
        Matcher yearMonth = YEAR_MONTH.matcher(text);
        while (yearMonth.find()) {
            int month = Integer.parseInt(yearMonth.group(2));
            if (month >= 1 && month <= 12) {
                points.add(new int[]{yearMonth.start(), Integer.parseInt(yearMonth.group(1)), month});
                for (int i = yearMonth.start(); i < yearMonth.end(); i++) {
                    rest.setCharAt(i, ' ');
                }
            }
        }
        Matcher yearOnly = YEAR_ONLY.matcher(rest);
        while (yearOnly.find()) {
            points.add(new int[]{yearOnly.start(), Integer.parseInt(yearOnly.group(1)), 1});
        }
        if (points.size() < 2) {
            return -1;
        }
        points.sort((a, b) -> Integer.compare(a[0], b[0]));

        int total = 0;
        for (int i = 0; i + 1 < points.size(); i += 2) {
            int start = points.get(i)[1] * 12 + points.get(i)[2];
            int end = points.get(i + 1)[1] * 12 + points.get(i + 1)[2];
            if (end > start) {
                total += end - start;
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- 学历维度（4）

    /**
     * 学历与经验要求（《数据库设计》6.4）：等级比例计分——
     * La ≥ Lr → 100；La &lt; Lr → round(100·La/Lr)（大专对本科 = 67，呈现为部分匹配）；
     * 简历无任何可识别学历 → 0 分并写入「未体现」文案。
     */
    private static int scoreEducation(String requirement, ResumeAnalysis resume,
                                      List<ScoringEvidence> evidences) {
        if (isNoRequirement(requirement)) {
            return 100;
        }
        int required = educationLevel(requirement);
        if (required <= 0) {
            // 解析不出等级关键词：按无要求处理（《数据库设计》6.5）
            return 100;
        }
        int actual = 0;
        for (EducationItem item : nullSafe(resume.getEducation())) {
            actual = Math.max(actual, educationLevel(item.getDegree()));
        }
        if (actual <= 0) {
            evidences.add(new ScoringEvidence(MatchDimension.EDUCATION, "学历",
                    ScoringEvidence.Status.MISSING, null));
            return 0;
        }
        if (actual >= required) {
            return 100;
        }
        return (int) Math.round(100.0 * actual / required);
    }

    /** 学历等级：博士 5 / 硕士·研究生 4 / 本科·学士 3 / 大专·专科·高职 2 / 中专·高中 1；无法识别 0 */
    private static int educationLevel(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String value = text.replaceAll("\\s+", "");
        if (value.contains("博士")) {
            return 5;
        }
        if (value.contains("硕士") || value.contains("研究生")) {
            return 4;
        }
        if (value.contains("本科") || value.contains("学士")) {
            return 3;
        }
        if (value.contains("大专") || value.contains("专科") || value.contains("高职")) {
            return 2;
        }
        if (value.contains("中专") || value.contains("高中")) {
            return 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 证据视图

    /** 简历侧全量证据（必备技能 / 加分技能维度）：skills + projects + experience + summary */
    private static ResumeTerms fullTerms(ResumeAnalysis resume) {
        List<Evidence> direct = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        weak.add(nullToEmpty(resume.getSummary()));
        for (SkillItem skill : nullSafe(resume.getSkills())) {
            direct.add(new Evidence(skill.getName(), "skills.name"));
            weak.add(nullToEmpty(skill.getEvidence()));
        }
        for (ProjectItem project : nullSafe(resume.getProjects())) {
            String origin = "project:" + nullToEmpty(project.getName());
            for (String technology : nullSafe(project.getTechnologies())) {
                direct.add(new Evidence(technology, origin));
            }
            weak.add(nullToEmpty(project.getDescription()));
        }
        for (ExperienceItem experience : nullSafe(resume.getExperience())) {
            weak.add(nullToEmpty(experience.getCompany()) + " " + nullToEmpty(experience.getPosition())
                    + " " + nullToEmpty(experience.getDescription()));
        }
        return new ResumeTerms(direct, weak);
    }

    /**
     * 项目相关性维度的证据：**判定范围限定在 projects 字段内**（《数据库设计》6.4）——
     * 直接证据 = technologies / name / role，弱证据 = description。
     * 与必备技能的差异在于本条要求"项目中有落点"，仅技能列表自述不算。
     */
    private static ResumeTerms projectTerms(ResumeAnalysis resume) {
        List<Evidence> direct = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (ProjectItem project : nullSafe(resume.getProjects())) {
            String origin = "project:" + nullToEmpty(project.getName());
            for (String technology : nullSafe(project.getTechnologies())) {
                direct.add(new Evidence(technology, origin));
            }
            direct.add(new Evidence(project.getName(), origin));
            direct.add(new Evidence(project.getRole(), origin));
            weak.add(nullToEmpty(project.getDescription()));
        }
        return new ResumeTerms(direct, weak);
    }

    // ---------------------------------------------------------------- 工具

    private static boolean isNoRequirement(String requirement) {
        return requirement == null || requirement.isBlank()
                || NO_REQUIREMENT.matcher(requirement).find();
    }

    /** 五个维度均未提取到要求时的退化情形（《数据库设计》6.5）：总分 100，仅记 WARN，不报错 */
    private void warnIfNoRequirement(JobAnalysis job) {
        boolean any = !nonBlank(job.getRequiredSkills()).isEmpty()
                || !nonBlank(job.getTechnologyStack()).isEmpty()
                || !nonBlank(job.getPreferredSkills()).isEmpty()
                || !isNoRequirement(job.getExperienceRequirement())
                || !isNoRequirement(job.getEducationRequirement());
        if (!any) {
            log.warn("岗位分析未提取到任何维度要求，总分按无要求规则记为 100：jobId={}", job.getJobId());
        }
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value.signum() < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.compareTo(MAX_SCORE) > 0) {
            return MAX_SCORE;
        }
        return value;
    }

    private static List<String> nonBlank(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static void addAll(Set<String> target, List<String> values) {
        for (String value : nonBlank(values)) {
            target.add(value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

}
