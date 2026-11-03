package com.careerpilot.service.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.entity.InterviewQuestion;

/**
 * 降级总评（M15，《数据库设计》5.14）：模型生成失败时，由**已落库的逐题点评与建议**
 * 确定性拼装总评——让"失败"可见（`summarySource = DEGRADED`），而不是静默缺失。
 *
 * <p>三条纪律（实现与文案均不得违背）：
 * <ol>
 *   <li><b>不伪造分数</b>——不产生任何新数字，平均分一律引用已落库的总分；
 *   <li><b>不新增事实</b>——强项 / 薄弱项与建议直接取自逐题点评与建议的截取、汇聚，
 *       不推断候选人能力、不编造经历；
 *   <li><b>明确标注来源</b>——总评首句写明"系统汇总生成"，避免被误读为模型生成。
 * </ol>
 */
@Component
public class InterviewSummaryFallback {

    /** 强项 / 薄弱项条数上限（与总评口径一致地取小值） */
    private static final int TAG_LIMIT = 3;

    /** 单条标签 / 建议的文本截取长度 */
    private static final int TEXT_LIMIT = 40;

    /** 建议汇聚条数上限 */
    private static final int RECOMMENDATION_LIMIT = 5;

    /** 由逐题点评拼装降级总评（调用方保证 questions 为已作答题目） */
    public InterviewSummaryAiOutput build(List<InterviewQuestion> questions, BigDecimal totalScore) {
        List<InterviewQuestion> scored = questions.stream()
                .filter(question -> question.getScore() != null)
                .toList();

        InterviewSummaryAiOutput output = new InterviewSummaryAiOutput();
        output.setSummary(buildSummaryText(questions.size(), scored.size(), totalScore));
        output.setStrengths(buildTags(scored, true));
        output.setWeaknesses(buildTags(scored, false));
        output.setRecommendations(buildRecommendations(scored));
        return output;
    }

    private static String buildSummaryText(int totalCount, int scoredCount, BigDecimal totalScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("本次总评由系统汇总生成（模型生成失败时的降级结果，内容来自逐题点评）。");
        sb.append("共 ").append(totalCount).append(" 题，平均分 ")
                .append(totalScore == null ? "—" : totalScore.toPlainString()).append(" 分");
        if (scoredCount < totalCount) {
            sb.append("（已评价 ").append(scoredCount).append(" 题）");
        }
        sb.append("。可在报告页点击「重新生成总评」获取模型版总评。");
        return sb.toString();
    }

    /** 强项 / 薄弱项：按得分排序取两极题的点评截断——只引用、不推断 */
    private static List<String> buildTags(List<InterviewQuestion> scored, boolean strengths) {
        if (scored.isEmpty()) {
            return List.of();
        }
        List<InterviewQuestion> sorted = new ArrayList<>(scored);
        sorted.sort(Comparator.comparing(InterviewQuestion::getScore).reversed());
        List<InterviewQuestion> picked = strengths ? sorted : sorted.reversed();
        Set<String> tags = new LinkedHashSet<>();
        for (InterviewQuestion question : picked) {
            if (tags.size() >= TAG_LIMIT) {
                break;
            }
            String analysis = question.getAnalysis();
            if (analysis == null || analysis.isBlank()) {
                continue;
            }
            tags.add(truncate(analysis.replaceAll("\\s+", " ").strip()));
        }
        return List.copyOf(tags);
    }

    /** 提升建议：汇聚逐题建议（去重、限量），不新增内容 */
    private static List<String> buildRecommendations(List<InterviewQuestion> scored) {
        Set<String> recommendations = new LinkedHashSet<>();
        for (InterviewQuestion question : scored) {
            if (recommendations.size() >= RECOMMENDATION_LIMIT) {
                break;
            }
            List<String> suggestions = question.getSuggestions();
            if (suggestions == null) {
                continue;
            }
            for (String suggestion : suggestions) {
                if (recommendations.size() >= RECOMMENDATION_LIMIT) {
                    break;
                }
                if (suggestion != null && !suggestion.isBlank()) {
                    recommendations.add(truncate(suggestion.replaceAll("\\s+", " ").strip()));
                }
            }
        }
        if (recommendations.isEmpty()) {
            // 逐题建议缺失（不应发生：有评价即有建议）时的兜底句——不涉及具体能力判断
            recommendations.add("按逐题点评逐条改进，并在下一场模拟面试中验证效果。");
        }
        return List.copyOf(recommendations);
    }

    private static String truncate(String text) {
        return text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT) + "…" : text;
    }

}
