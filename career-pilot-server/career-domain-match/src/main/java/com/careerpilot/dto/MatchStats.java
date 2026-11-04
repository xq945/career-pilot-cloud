package com.careerpilot.dto;

/**
 * 仪表盘所需的匹配统计（阶段 7 内部 API）。
 *
 * @param count     本人匹配记录数
 * @param bestScore 历史最高分；无记录时为 null
 */
public record MatchStats(long count, Double bestScore) {
}
