package com.careerpilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.MatchDimension;

/**
 * 评分配置的启动期护栏（《数据库设计》6.1）。
 *
 * 权重是总分公式的系数：配置错了不会报错，只会**静默算出错误的分数**——
 * 因此所有非法组合都必须在构造（即应用启动）时被拒绝。
 */
class MatchScoringPropertiesTest {

    @Test
    void weights_acceptsValidSum() {
        MatchScoringProperties.Weights weights = new MatchScoringProperties.Weights(45, 25, 15, 10, 5);
        assertEquals(45, weights.getRequiredSkills());
        assertEquals(5, weights.getPreferredSkills());
    }

    @Test
    void weights_rejectsSumOtherThan100() {
        assertThrows(IllegalStateException.class,
                () -> new MatchScoringProperties.Weights(45, 25, 15, 10, 4));
    }

    @Test
    void weights_rejectsNegativeValue() {
        assertThrows(IllegalStateException.class,
                () -> new MatchScoringProperties.Weights(-1, 51, 15, 10, 25));
    }

    @Test
    void constructor_rejectsBlankRuleVersion() {
        MatchScoringProperties.Weights weights = new MatchScoringProperties.Weights(45, 25, 15, 10, 5);
        assertThrows(IllegalStateException.class, () -> new MatchScoringProperties("  ", weights));
        assertThrows(IllegalStateException.class, () -> new MatchScoringProperties(null, weights));
    }

    @Test
    void constructor_rejectsMissingWeights() {
        assertThrows(IllegalStateException.class, () -> new MatchScoringProperties("v1", null));
    }

    @Test
    void orderByWeightDesc_keepsDeclarationOrderForTies() {
        MatchScoringProperties properties = new MatchScoringProperties("v1",
                new MatchScoringProperties.Weights(40, 30, 30, 0, 0));
        // 同权重的 two 个维度保持枚举声明序（稳定排序）
        assertEquals(List.of(MatchDimension.REQUIRED_SKILLS, MatchDimension.PROJECT_RELEVANCE,
                        MatchDimension.EXPERIENCE, MatchDimension.EDUCATION, MatchDimension.PREFERRED_SKILLS),
                properties.orderByWeightDesc());
    }

}
