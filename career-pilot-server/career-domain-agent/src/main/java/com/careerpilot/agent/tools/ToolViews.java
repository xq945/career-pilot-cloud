package com.careerpilot.agent.tools;

import java.util.List;

import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.dto.ai.SkillItem;

/**
 * 工具输出的精简投影（《Agent设计》§4.2 第 6 条：只返回适合模型阅读的精简结果，
 * 不把完整简历原文或大段结构化结果再次传给模型——既控 token 成本，也缩小注入面）。
 *
 * <p>与 {@code StructuredOutputSanitizer} 的分工：那个清洗的是**模型输出**（要记 WARN 日志），
 * 这里投影的是**已落库的业务结果**（静默截断即可）。
 */
final class ToolViews {

    /** 单个文本字段的投影上限（字符） */
    static final int TEXT_LIMIT = 500;

    /** 列表字段的投影上限（条数） */
    static final int LIST_LIMIT = 10;

    private ToolViews() {
    }

    static String text(String value) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > TEXT_LIMIT ? flat.substring(0, TEXT_LIMIT) + "…" : flat;
    }

    static List<String> texts(List<String> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().limit(LIST_LIMIT).map(ToolViews::text).toList();
    }

    static <T> List<T> list(List<T> source) {
        if (source == null) {
            return List.of();
        }
        return source.size() > LIST_LIMIT ? List.copyOf(source.subList(0, LIST_LIMIT)) : List.copyOf(source);
    }

    /** 技能项投影为 name/level 两个字段（evidence 属长文本，不传给模型） */
    static List<String> skills(List<SkillItem> skills) {
        if (skills == null) {
            return List.of();
        }
        return skills.stream().limit(LIST_LIMIT)
                .map(skill -> skill.getName() == null ? "" : skill.getName()
                        + (skill.getLevel() == null || skill.getLevel().isBlank() ? "" : "（" + skill.getLevel() + "）"))
                .filter(text -> !text.isBlank())
                .toList();
    }

    /** 匹配明细投影为"文本（维度）"的单行形式 */
    static List<String> matchItems(List<MatchItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().limit(LIST_LIMIT)
                .map(item -> (item.getText() == null ? "" : text(item.getText()))
                        + (item.getDimension() == null ? "" : "（" + item.getDimension() + "）"))
                .filter(text -> !text.isBlank())
                .toList();
    }

}
