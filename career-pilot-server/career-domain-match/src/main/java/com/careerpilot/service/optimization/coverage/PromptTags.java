package com.careerpilot.service.optimization.coverage;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型输出中偶尔混入的 Prompt 结构标签（如 {@code 【简历原文】}）。
 *
 * <p>只剥离行首的已知标签，剥离后仍由事实校验按原文/优化稿做包含性检查，
 * 不改变事实边界；未知或正文中的方括号内容原样保留。
 */
public final class PromptTags {

    private static final Set<String> TAGS = Set.of(
            "简历原文", "简历内容", "岗位描述", "岗位要求", "优化要求", "输出要求");

    private PromptTags() {
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text;
        for (String tag : TAGS) {
            value = value.replaceAll("(?m)^\\s*【" + Pattern.quote(tag) + "】\\s*", "");
        }
        return value;
    }
}
