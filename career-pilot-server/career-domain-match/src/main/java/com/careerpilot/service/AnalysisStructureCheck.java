package com.careerpilot.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 历史分析结果的结构校验（《设计审查报告》S2、《数据库设计》6.6）。
 *
 * <p>**为什么需要它**：分析结果是模型生成、落库为 JSON 的；匹配时被再次读取使用。
 * 若存量数据存在形状缺陷（缺字段、空白串、超长列表），会把脏数据扩散进新的匹配记录
 * 与模型上下文。因此在"读取后、使用前"做一次轻量清洗。
 *
 * <p>处置原则（同《Agent设计》6.4「能补的补、能截的截、补不了才丢」）：
 * null 列表补空、空白串归一 null（否则会被误判为"岗位有要求"）、缺必需字段的元素丢弃、
 * 超长列表截断。**只清洗内存对象并记脱敏日志，不写回数据库、不因此报错**。
 */
public final class AnalysisStructureCheck {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStructureCheck.class);

    /** 列表字段上限（与《Agent设计》6.4 的落库前校验一致） */
    private static final int MAX_ITEMS = 50;

    private AnalysisStructureCheck() {
    }

    /** 简历分析的结构清洗（读取后、计分与解释前调用） */
    public static void sanitize(ResumeAnalysis analysis, String scene) {
        if (analysis == null) {
            return;
        }
        analysis.setSummary(blankToNull(analysis.getSummary()));
        analysis.setSkills(cleanSkills(analysis.getSkills(), scene, "skills"));
        analysis.setEducation(cleanItems(analysis.getEducation(), scene, "education"));
        analysis.setProjects(cleanItems(analysis.getProjects(), scene, "projects"));
        analysis.setExperience(cleanItems(analysis.getExperience(), scene, "experience"));
        analysis.setAdvantages(cleanStrings(analysis.getAdvantages(), scene, "advantages"));
        analysis.setWeaknesses(cleanStrings(analysis.getWeaknesses(), scene, "weaknesses"));
        analysis.setSuggestions(cleanStrings(analysis.getSuggestions(), scene, "suggestions"));
    }

    /** 岗位分析的结构清洗 */
    public static void sanitize(JobAnalysis analysis, String scene) {
        if (analysis == null) {
            return;
        }
        analysis.setSummary(blankToNull(analysis.getSummary()));
        analysis.setResponsibilities(cleanStrings(analysis.getResponsibilities(), scene, "responsibilities"));
        analysis.setRequiredSkills(cleanStrings(analysis.getRequiredSkills(), scene, "requiredSkills"));
        analysis.setPreferredSkills(cleanStrings(analysis.getPreferredSkills(), scene, "preferredSkills"));
        analysis.setTechnologyStack(cleanStrings(analysis.getTechnologyStack(), scene, "technologyStack"));
        analysis.setSoftSkills(cleanStrings(analysis.getSoftSkills(), scene, "softSkills"));
        analysis.setEducationRequirement(blankToNull(analysis.getEducationRequirement()));
        analysis.setExperienceRequirement(blankToNull(analysis.getExperienceRequirement()));
    }

    // ---------------------------------------------------------------- 工具

    /** 字符串列表：null → 空列表；过滤 null 与空白元素；截断到上限 */
    private static List<String> cleanStrings(List<String> values, String scene, String field) {
        if (values == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                cleaned.add(normalized);
            }
        }
        return limit(cleaned, scene, field);
    }

    /** 对象列表：null → 空列表；过滤 null 元素；截断到上限 */
    private static <T> List<T> cleanItems(List<T> values, String scene, String field) {
        if (values == null) {
            return List.of();
        }
        List<T> cleaned = values.stream().filter(value -> value != null).toList();
        return limit(cleaned, scene, field);
    }

    /** 技能项：额外要求 {@code name} 非空白（缺失时整条丢弃，《Agent设计》6.4） */
    private static List<SkillItem> cleanSkills(List<SkillItem> skills, String scene, String field) {
        List<SkillItem> cleaned = new ArrayList<>();
        int dropped = 0;
        for (SkillItem skill : cleanItems(skills, scene, field)) {
            if (blankToNull(skill.getName()) == null) {
                dropped++;
            }
            else {
                cleaned.add(skill);
            }
        }
        if (dropped > 0) {
            log.warn("结构校验：技能项缺少 name，已丢弃（scene={} 条数={}）", scene, dropped);
        }
        return limit(cleaned, scene, field);
    }

    private static <T> List<T> limit(List<T> values, String scene, String field) {
        if (values.size() <= MAX_ITEMS) {
            return values;
        }
        log.warn("结构校验：字段超过上限被截断（scene={} field={} 原长度={}）", scene, field, values.size());
        return List.copyOf(values.subList(0, MAX_ITEMS));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
