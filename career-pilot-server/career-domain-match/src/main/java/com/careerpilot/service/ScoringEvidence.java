package com.careerpilot.service;

import com.careerpilot.dto.MatchDimension;

/**
 * 评分证据账本的一条记录（《数据库设计》6.6「每维度分数可追溯到具体证据」）。
 *
 * <p>账本**不落库、不进接口响应**，用途有三：
 * <ol>
 *   <li>作为模型解释的事实输入（《Agent设计》6.3）；</li>
 *   <li>模型漏掉的差距/部分匹配项由后端按账本补全（文案受控，见 {@link #explanation()}）；</li>
 *   <li>单元测试直接断言「术语 → 档位 → 来源」三要素。</li>
 * </ol>
 *
 * @param dimension 该证据影响的维度
 * @param term      岗位要求术语原文（文案与去重判定均以它为准）
 * @param status    判定档位
 * @param source    证据来源（如 {@code skills.name} / {@code project:电商平台}），无证据时为 null
 */
public record ScoringEvidence(MatchDimension dimension, String term, Status status, String source) {

    /** 单条岗位要求的判定档位（与《数据库设计》6.3 的三档一一对应） */
    public enum Status {
        /** 直接证据（1.0） */
        MATCHED,
        /** 部分证据（0.5） */
        PARTIAL,
        /** 无证据（0） */
        MISSING
    }

    /**
     * 后端补全文案（受控模板，**必须含「未体现」「缺少」等措辞**，《数据库设计》6.5）。
     *
     * <p>仅 PARTIAL 与 MISSING 会被用于补全（模型漏掉明细项时按账本追加）；
     * MATCHED 的文案不参与补全，保留仅为完整性与测试断言方便。
     */
    public String explanation() {
        return switch (status) {
            case MATCHED -> term + "：简历中有对应证据";
            case PARTIAL -> term + "：简历中有相关表述，但缺少明确证据";
            case MISSING -> term + "：简历未体现相关证据";
        };
    }

}
