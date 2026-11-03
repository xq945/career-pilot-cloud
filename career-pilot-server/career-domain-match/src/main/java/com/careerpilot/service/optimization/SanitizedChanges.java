package com.careerpilot.service.optimization;

import java.util.ArrayList;
import java.util.List;

import com.careerpilot.dto.ai.ChangeItem;

/**
 * 清洗后的变更记录 + 清洗过程中的信息损失元数据（第三阶段修复 A）。
 *
 * <p>事实校验必须知道“模型原始 changes 在清洗中发生了什么”，否则只检查剩余项会
 * 静默放行未核对的正文改动。本对象只保存计数与布尔标记，不保存被丢弃的正文，
 * 避免把完整模型响应/用户简历写入日志或审计表。
 *
 * @param changes               清洗后用于展示/落库的 changes
 * @param inputCount            模型返回的原始条数（含 null 元素）
 * @param keptCount             清洗后保留的条数（非 null）
 * @param nullCount             原始列表中的 null 元素数
 * @param missingSectionCount   因缺 section 被丢弃的条数
 * @param missingAfterCount     因缺 after（null/空白）被丢弃的条数
 * @param wordingGuardCount     因 WordingGuard 命中被丢弃的条数
 * @param overLimitDropped      因超过 30 条上限被丢弃的条数
 * @param evidenceTooLong       是否存在 before/after 超过 {@link #EVIDENCE_TEXT_LIMIT} 的证据
 * @param auxiliaryTruncated    是否存在 section/type/reason 被截断
 */
public record SanitizedChanges(
        List<ChangeItem> changes,
        int inputCount,
        int keptCount,
        int nullCount,
        int missingSectionCount,
        int missingAfterCount,
        int wordingGuardCount,
        int overLimitDropped,
        boolean evidenceTooLong,
        boolean auxiliaryTruncated) {

    /**
     * 单条 before/after 的上限（2026-10-21 由 500 上调至 2000）。
     *
     * <p>500 时 PDF 简历的长段落（归一化合并后可达 1800 字符）被模型整段作为
     * before 复制，清洗直接判定「证据超长」并拒绝，任务无法成功；而长证据
     * 在事实校验中需要**完整**出现在原文，比短证据更严格，不削弱闸门。
     * 提示词仍要求尽量只摘取最小必要片段。
     */
    public static final int EVIDENCE_TEXT_LIMIT = 2000;

    /** 辅助字段（section / type / reason）上限：只用于展示，保持原有 500 字符口径 */
    public static final int AUXILIARY_TEXT_LIMIT = 500;

    /**
     * 直接校验入口的兼容构造：认为传入列表就是完整证据（不经过清洗截断）。
     * null 元素与超长 before/after 仍会被标记，避免测试或新调用方绕过。
     */
    public static SanitizedChanges of(List<ChangeItem> changes) {
        List<ChangeItem> source = changes == null ? List.of() : changes;
        int nulls = 0;
        int kept = 0;
        boolean overlong = false;
        for (ChangeItem item : source) {
            if (item == null) {
                nulls++;
                continue;
            }
            kept++;
            if (length(item.getBefore()) > EVIDENCE_TEXT_LIMIT
                    || length(item.getAfter()) > EVIDENCE_TEXT_LIMIT) {
                overlong = true;
            }
        }
        return new SanitizedChanges(new ArrayList<>(source), source.size(), kept, nulls,
                0, 0, 0, 0, overlong, false);
    }

    public boolean hasInformationLoss() {
        return nullCount > 0
                || missingSectionCount > 0
                || missingAfterCount > 0
                || wordingGuardCount > 0
                || overLimitDropped > 0
                || evidenceTooLong
                || auxiliaryTruncated;
    }

    /** 面向任务失败原因的安全摘要：只有计数，没有正文 */
    public String summary() {
        return "变更记录清洗存在信息损失（输入 " + inputCount + " 条，保留 " + keptCount
                + " 条，null " + nullCount
                + "，缺 section " + missingSectionCount
                + "，缺 after " + missingAfterCount
                + "，措辞守卫 " + wordingGuardCount
                + "，超上限 " + overLimitDropped
                + (evidenceTooLong ? "，before/after 超长" : "")
                + (auxiliaryTruncated ? "，辅助字段截断" : "") + "）";
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
