package com.careerpilot.service;

import java.math.BigDecimal;
import java.util.List;

import com.careerpilot.dto.MatchDimensionScores;

/**
 * 评分结果（《数据库设计》6.6）：总分与维度分落库、证据账本仅供解释与补全。
 *
 * @param totalScore      总分（两位小数，**落库唯一来源**；模型不得覆盖，《系统架构设计》6.2）
 * @param dimensionScores 维度分（落库即响应，结构见《数据库设计》5.6）
 * @param evidences       证据账本（不落库、不进响应）
 */
public record MatchScoringResult(BigDecimal totalScore, MatchDimensionScores dimensionScores,
                                 List<ScoringEvidence> evidences) {
}
