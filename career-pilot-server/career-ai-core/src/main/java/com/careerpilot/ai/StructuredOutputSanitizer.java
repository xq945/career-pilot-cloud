package com.careerpilot.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型输出清洗工具（《Agent设计》6.4 的落地，M5–M7 复用）。
 *
 * 原则：**能补的补、能截的截、补不了才拒**——超限只截断不拒绝，只有无法构成可用结果
 * （非法 JSON、类型不符）才由 {@link AiCallTemplate} 判为 50201。
 *
 * 所有截断与丢弃都记 WARN 日志（字段名、原长度、被丢弃的字段名），
 * **不含内容**，用于 Prompt 调优（《Agent设计》6.4 原则 2）。
 * 缺失的可选字段补 null 由 Jackson 天然完成（无参构造 + null 字段），无需代码。
 */
public final class StructuredOutputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputSanitizer.class);

    private StructuredOutputSanitizer() {
    }

    /**
     * 文本超长截断到上限（如 summary ≤ 2000 字符）。
     *
     * @return 截断后的文本；传入 null 时原样返回
     */
    public static String limitText(String text, int max, String field, String scene) {
        if (text == null || text.length() <= max) {
            return text;
        }
        log.warn("AI 输出清洗：文本超限截断 scene={} field={} 原长度={} 截断至={}",
                scene, field, text.length(), max);
        return text.substring(0, max);
    }

    /**
     * 列表超限截断到上限（如各列表字段 ≤ 50 项）。
     *
     * @return 截断后的新列表；传入 null 或空列表时原样返回
     */
    public static <T> List<T> limitList(List<T> list, int max, String field, String scene) {
        if (list == null || list.size() <= max) {
            return list;
        }
        int original = list.size();
        log.warn("AI 输出清洗：列表超限截断 scene={} field={} 原长度={} 截断至={}",
                scene, field, original, max);
        return new ArrayList<>(list.subList(0, max));
    }

    /**
     * 逐元素清洗：记录并丢弃元素内约定外的字段（{@link AiOutputElement} 收集），
     * 并按需丢弃缺关键字段的元素（如 skills 元素缺 name）。
     *
     * @param requiredKey     取关键字段的函数；传 null 表示不检查完整性（只清约定外字段）
     * @param requiredKeyName 关键字段名（仅用于日志）
     * @return 清洗后的新列表；传入 null 或空列表时原样返回
     */
    public static <T extends AiOutputElement> List<T> cleanElements(
            List<T> list, Function<T, String> requiredKey, String requiredKeyName,
            String field, String scene) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<T> kept = new ArrayList<>(list.size());
        int dropped = 0;
        for (T item : list) {
            Map<String, Object> unknown = item.drainUnknownFields();
            if (!unknown.isEmpty()) {
                log.warn("AI 输出清洗：丢弃约定外字段 scene={} field={} 字段名={}",
                        scene, field, unknown.keySet());
            }
            if (requiredKey != null) {
                String key = requiredKey.apply(item);
                if (key == null || key.isBlank()) {
                    dropped++;
                    continue;
                }
            }
            kept.add(item);
        }
        if (dropped > 0) {
            log.warn("AI 输出清洗：丢弃结构不完整的元素 scene={} field={} 缺字段={} 丢弃数={}",
                    scene, field, requiredKeyName, dropped);
        }
        return kept;
    }

}
