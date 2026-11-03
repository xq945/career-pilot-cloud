package com.careerpilot.config;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.careerpilot.dto.MatchDimension;

/**
 * 匹配评分配置（《数据库设计》6.1「权重入配置」、《开发顺序》M6）。
 *
 * <p>配置错误（权重之和不等于 100、出现负权重、ruleVersion 缺失）必须**在启动时**暴露，
 * 而不是等到首次匹配才失败——延续 FileStorageProperties 与提示词加载的 fail-fast 惯例。
 *
 * <p>ruleVersion 的透传点：报告落库与响应中的 {@code dimensionScores.ruleVersion} 均取自此处；
 * 调整权重或算法时必须手工递增它（《数据库设计》5.6）。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**：
 * 组件扫描注册的配置类走构造器自动装配，会把构造参数当成待注入的 bean 而启动失败。
 */
@ConfigurationProperties(prefix = "app.match")
public class MatchScoringProperties {

    private final String ruleVersion;

    private final Weights weights;

    /**
     * 构造器绑定（Spring Boot 的 relaxed binding：yml 中的 kebab-case 键绑定到驼峰构造参数）。
     * 校验放在构造器：配置不合法即启动失败。
     */
    public MatchScoringProperties(String ruleVersion, Weights weights) {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalStateException("app.match.rule-version 未配置（《数据库设计》6.1）");
        }
        if (weights == null) {
            throw new IllegalStateException("app.match.weights 未配置（《数据库设计》6.1）");
        }
        this.ruleVersion = ruleVersion;
        this.weights = weights;
    }

    /** 当前评分规则版本（随报告记录；权重或算法调整时递增） */
    public String ruleVersion() {
        return ruleVersion;
    }

    /** 指定维度的权重（百分数） */
    public int weightOf(MatchDimension dimension) {
        return switch (dimension) {
            case REQUIRED_SKILLS -> weights.getRequiredSkills();
            case PROJECT_RELEVANCE -> weights.getProjectRelevance();
            case EXPERIENCE -> weights.getExperience();
            case EDUCATION -> weights.getEducation();
            case PREFERRED_SKILLS -> weights.getPreferredSkills();
        };
    }

    /**
     * 维度按权重降序；同权重按枚举声明序。
     *
     * <p>{@code List.sort} 是稳定排序，{@code reversed()} 比较器对相等元素返回 0，
     * 因此同权重维度的相对顺序保持枚举声明序（《数据库设计》6.1 的顺序约定）。
     */
    public List<MatchDimension> orderByWeightDesc() {
        return Arrays.stream(MatchDimension.values())
                .sorted(Comparator.comparingInt(this::weightOf).reversed())
                .toList();
    }

    /** 五维权重（与 application.yml 的 {@code app.match.weights} 一一对应） */
    public static class Weights {

        private final int requiredSkills;

        private final int projectRelevance;

        private final int experience;

        private final int education;

        private final int preferredSkills;

        public Weights(int requiredSkills, int projectRelevance, int experience, int education,
                       int preferredSkills) {
            int[] all = {requiredSkills, projectRelevance, experience, education, preferredSkills};
            for (int weight : all) {
                if (weight < 0) {
                    throw new IllegalStateException("app.match.weights 不允许负权重：" + weight);
                }
            }
            int sum = requiredSkills + projectRelevance + experience + education + preferredSkills;
            if (sum != 100) {
                throw new IllegalStateException("app.match.weights 各项之和必须为 100，当前为 " + sum);
            }
            this.requiredSkills = requiredSkills;
            this.projectRelevance = projectRelevance;
            this.experience = experience;
            this.education = education;
            this.preferredSkills = preferredSkills;
        }

        public int getRequiredSkills() {
            return requiredSkills;
        }

        public int getProjectRelevance() {
            return projectRelevance;
        }

        public int getExperience() {
            return experience;
        }

        public int getEducation() {
            return education;
        }

        public int getPreferredSkills() {
            return preferredSkills;
        }

    }

}
