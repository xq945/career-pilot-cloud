package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.careerpilot.service.optimization.numeric.NumericMention;
import com.careerpilot.service.optimization.numeric.NumericMentionExtractor;

class FactTokenExtractorTest {

    private final FactTokenExtractor extractor = new FactTokenExtractor();

    private final NumericMentionExtractor numeric = new NumericMentionExtractor();

    @Test
    void extractsAllFactKindsFromOneBlock() {
        String text = "2025年3月入职江西云牛科技，使用 Redis，主导接口开发";

        List<FactToken> tokens = extractor.extract(text, numeric.extract(text), "项目求职平台");

        assertEquals(Set.of("2025-03"), extractor.values(tokens, FactToken.Kind.DATE));
        assertTrue(extractor.values(tokens, FactToken.Kind.PROPER_NOUN).stream()
                .anyMatch(value -> value.endsWith("云牛")));
        assertEquals(Set.of("redis"), extractor.values(tokens, FactToken.Kind.TECH_TERM));
        assertTrue(extractor.values(tokens, FactToken.Kind.OWNERSHIP).contains("主导"));
        assertEquals(Set.of("项目求职平台"),
                extractor.values(tokens, FactToken.Kind.PROJECT));
    }

    @Test
    void numericTokensReuseRepairBMentions() {
        String text = "负责 3 个模块，响应时间 54 毫秒";

        List<FactToken> tokens = extractor.extract(text, numeric.extract(text), "");
        Set<String> numbers = extractor.values(tokens, FactToken.Kind.NUMBER);

        assertEquals(Set.of("3", "54"), numbers);
    }

    @Test
    void englishStopWordsAreNotTechnicalTokens() {
        List<FactToken> tokens = extractor.extract(
                "Project with Kubernetes and Redis", numeric.extract(""), "");

        Set<String> terms = extractor.values(tokens, FactToken.Kind.TECH_TERM);
        assertEquals(Set.of("kubernetes", "redis"), terms);
    }

    @Test
    void duplicateTokenValuesAreCollapsed() {
        String text = "Redis Redis Redis";

        List<FactToken> tokens = extractor.extract(text, numeric.extract(text), "");

        assertEquals(1, tokens.stream().filter(t -> t.kind() == FactToken.Kind.TECH_TERM).count());
    }

    @Test
    void sharedLexiconMatchesFactCheckerRules() {
        assertTrue(FactTokenExtractor.OWNERSHIP_ESCALATIONS.contains("负责"));
        assertTrue(FactTokenExtractor.ENGLISH_STOP_WORDS.contains("platform"));
        assertTrue(FactTokenExtractor.PROPER_NOUN.matcher("阿里巴巴公司").find());
    }

    @Test
    void properNounNoiseIsSupportedByLongSubstring() {
        String source = CoverageText.canonical(
                "JWT 实现无状态认证；第十六届蓝桥杯全国软件和信息技术专业人才大赛");

        assertTrue(FactTokenExtractor.properNounSupported(source, "jwt无状态"));
        assertTrue(FactTokenExtractor.properNounSupported(source, "并在蓝桥杯等编程"));
        assertFalse(FactTokenExtractor.properNounSupported(source, "阿里巴巴"));
        assertFalse(FactTokenExtractor.properNounSupported(source, "清华大学"));
    }

    @Test
    void projectTokenHasNoTextRange() {
        List<FactToken> tokens = extractor.extract("参与接口开发", numeric.extract("参与接口开发"),
                "项目a");

        FactToken project = tokens.stream()
                .filter(t -> t.kind() == FactToken.Kind.PROJECT)
                .findFirst()
                .orElseThrow();
        assertEquals(-1, project.start());
        assertEquals(-1, project.end());
    }
}
