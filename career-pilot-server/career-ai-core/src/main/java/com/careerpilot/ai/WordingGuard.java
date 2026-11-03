package com.careerpilot.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表述守卫（《前端原型设计》5.8 的措辞硬性要求、《Agent设计》6.4）。
 *
 * <p>命中「断言能力 / 录用概率」类措辞的文本一律丢弃并记 WARN——缺少简历证据只能表述为
 * 「简历未体现」「缺少相关证据」，不得写成「不具备」；匹配分数不得表述为录用概率。
 *
 * <p>M6 时该规则内联在 {@code MatchAiService}；M11 起抽为共享常量与方法：
 * 匹配解释与简历优化两处共用同一份禁用词表，避免两处维护漂移。
 */
public final class WordingGuard {

    private static final Logger log = LoggerFactory.getLogger(WordingGuard.class);

    /** 禁用词（断言能力 / 录用概率类；命中即丢弃该条文本） */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "不具备", "没有该能力", "没有这项能力", "没有这项技能",
            "录用概率", "通过率", "招聘结果预测", "成功率");

    private WordingGuard() {
    }

    /**
     * 校验文本表述：命中禁用词即记 WARN 并返回 {@code null}（调用方据此丢弃该条文本），
     * 否则原样返回；传入 {@code null} 时返回 {@code null}。
     *
     * @param scene 场景名（日志用）
     * @param field 字段名（日志用）
     */
    public static String guard(String text, String scene, String field) {
        if (text == null) {
            return null;
        }
        for (String forbidden : FORBIDDEN_PHRASES) {
            if (text.contains(forbidden)) {
                log.warn("输出清洗：措辞守卫命中，已丢弃文本 scene={} field={} 禁用词={}",
                        scene, field, forbidden);
                return null;
            }
        }
        return text;
    }

}
