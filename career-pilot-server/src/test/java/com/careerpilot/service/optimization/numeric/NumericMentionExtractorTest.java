package com.careerpilot.service.optimization.numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.service.optimization.numeric.NumericMention.Direction;
import com.careerpilot.service.optimization.numeric.NumericMention.Kind;
import com.careerpilot.service.optimization.numeric.NumericMention.UnitFamily;

class NumericMentionExtractorTest {

    private final NumericMentionExtractor extractor = new NumericMentionExtractor();

    @Test
    void normalizesThousandsAndChineseNumerals() {
        NumericMention grouped = only("系统累计处理 1,000 条数据，运行稳定。");
        NumericMention plain = only("系统累计处理 1000 条数据，运行稳定。");

        assertEquals(Kind.COUNT, grouped.kind());
        assertEquals("1000", grouped.value());
        assertEquals("条", grouped.unit());
        assertEquals("数据", grouped.objectKey());
        assertEquals(grouped.value(), plain.value());
        assertEquals(grouped.unit(), plain.unit());
        assertEquals(grouped.objectKey(), plain.objectKey());
        assertEquals(grouped.lineSignature(), plain.lineSignature());

        assertEquals("3", only("负责三个模块。").value());
        assertEquals("3", only("负责 3 个模块。").value());
        assertEquals("5", only("负责五个模块。").value());
    }

    @Test
    void bindsUserAndOrderCountsByPrefixObject() {
        List<NumericMention> mentions = extractor.extract("用户数 3 万，订单数 5 千。");

        assertEquals(2, mentions.size());
        assertEquals("30000", mentions.get(0).value());
        assertEquals("个", mentions.get(0).unit());
        assertEquals("用户", mentions.get(0).objectKey());
        assertEquals("5000", mentions.get(1).value());
        assertEquals("订单", mentions.get(1).objectKey());
        assertTrue(mentions.get(0).metricKey().isEmpty());
    }

    @Test
    void conjunctionStopsSuffixObjectAndKeepsAssociations() {
        List<NumericMention> mentions = extractor.extract("负责 3 个模块和 5 个接口。");

        assertEquals(2, mentions.size());
        assertEquals("模块", mentions.get(0).objectKey());
        assertEquals("接口", mentions.get(1).objectKey());
        assertFalse(mentions.get(0).objectKey().contains("和"));
    }

    @Test
    void bindsResponseTimeMetricAndSubject() {
        NumericMention response = only("接口响应时间 5 秒。");
        NumericMention elapsed = only("接口响应耗时 5 秒。");

        assertEquals("RESPONSE_LATENCY", response.metricKey());
        assertEquals("RESPONSE_LATENCY", elapsed.metricKey());
        assertEquals("接口", response.subjectKey());
        assertEquals("接口", elapsed.subjectKey());
        assertEquals("秒", response.unit());
        assertTrue(response.statisticKey().isEmpty(), "不应把无关文本误识别为 P95");
    }

    @Test
    void bindsRateAndObjectMetricsByClause() {
        List<NumericMention> mentions = extractor.extract(
                "接口响应时间为 5 秒，吞吐量为 100 QPS。");
        assertEquals("RESPONSE_LATENCY", mentions.get(0).metricKey());
        assertEquals("接口", mentions.get(0).subjectKey());
        assertEquals("QPS", mentions.get(1).unit());
        assertEquals("QPS", mentions.get(1).metricKey());

        List<NumericMention> rates = extractor.extract("提升转化率 30%，降低成本 20%。");
        assertEquals("TRANSFORM_RATE", rates.get(0).metricKey());
        assertEquals(Direction.UP, rates.get(0).direction());
        assertEquals("COST", rates.get(1).metricKey());
        assertEquals(Direction.DOWN, rates.get(1).direction());
    }

    @Test
    void datesAndVersionsDoNotBecomeBusinessCounts() {
        List<NumericMention> mentions = extractor.extract(
                "2025 年 3 月入职，负责 3 个模块；技术栈：Java 17、Spring Boot 3.5。");

        assertEquals(4, mentions.size());
        assertEquals(Kind.DATE, mentions.get(0).kind());
        assertEquals("2025-03", mentions.get(0).value());
        assertEquals(Kind.COUNT, mentions.get(1).kind());
        assertEquals("3", mentions.get(1).value());
        assertEquals(Kind.VERSION, mentions.get(2).kind());
        assertEquals("java", mentions.get(2).subjectKey());
        assertEquals("17", mentions.get(2).value());
        assertEquals(Kind.VERSION, mentions.get(3).kind());
        assertEquals("3.5", mentions.get(3).value());
        assertTrue(mentions.get(3).subjectKey().contains("springboot"));
    }

    @Test
    void supportsNegativeDecimalPercentAndGroupedValues() {
        List<NumericMention> mentions = extractor.extract(
                "利润率 -5%，工作 1.5 年，提升 30%，处理 1,000 条数据。");

        assertEquals(4, mentions.size());
        assertEquals("-5", mentions.get(0).value());
        assertEquals("%", mentions.get(0).unit());
        assertEquals(UnitFamily.PERCENT, mentions.get(0).unitFamily());
        assertEquals("1.5", mentions.get(1).value());
        assertEquals("年", mentions.get(1).unit());
        assertEquals("30", mentions.get(2).value());
        assertEquals("%", mentions.get(2).unit());
        assertEquals("1000", mentions.get(3).value());
        assertEquals("条", mentions.get(3).unit());
    }

    @Test
    void duplicateNumbersKeepTheirContexts() {
        List<NumericMention> mentions = extractor.extract("模块数 3 个。接口数 3 个。");

        assertEquals(2, mentions.size());
        assertEquals("模块", mentions.get(0).objectKey());
        assertEquals("接口", mentions.get(1).objectKey());
        assertEquals(mentions.get(0).value(), mentions.get(1).value());
    }

    @Test
    void supportsLabelCarryOverAfterColon() {
        NumericMention mention = only("用户数：3 万");

        assertEquals("30000", mention.value());
        assertEquals("用户", mention.objectKey());
        assertTrue(mention.metricKey().isEmpty());
    }

    @Test
    void marksUnsupportedExpressions() {
        List<NumericMention> mentions = extractor.extract("负责若干模块，完成三分之一。");

        assertEquals(2, mentions.size());
        assertEquals(Kind.UNSUPPORTED, mentions.get(0).kind());
        assertEquals(Kind.UNSUPPORTED, mentions.get(1).kind());
        assertFalse(mentions.get(0).supported());
        assertFalse(mentions.get(1).supported());
    }

    @Test
    void commonCompanyPhraseIsNotTreatedAsCountFact() {
        NumericMention mention = only("在上一家公司工作 1.5 年，负责接口开发。");

        assertEquals(Kind.DURATION, mention.kind());
        assertEquals("1.5", mention.value());
        assertEquals("年", mention.unit());
    }

    // ---------------------------------------------------------------- Repair B

    @Test
    void dataAndDatabaseAreNotBoundToTheSameObject() {
        NumericMention data = only("处理了 3 个数据");
        NumericMention database = only("处理了 3 个数据库");

        assertEquals("数据", data.objectKey());
        assertNotEquals(data.objectKey(), database.objectKey(),
                "“数据库”不能被截成“数据”");
    }

    @Test
    void crossLineProjectLabelsBecomeContext() {
        List<NumericMention> mentions = extractor.extract(
                "项目 A：开发 3 个模块\n项目 B：开发 5 个模块");

        assertEquals(2, mentions.size());
        assertTrue(mentions.get(0).contextSignature().contains("项目a"),
                () -> mentions.get(0).contextSignature());
        assertTrue(mentions.get(1).contextSignature().contains("项目b"),
                () -> mentions.get(1).contextSignature());
        assertNotEquals(mentions.get(0).contextSignature(), mentions.get(1).contextSignature());
    }

    @Test
    void markdownHeadingBecomesContextForFollowingLine() {
        NumericMention mention = only("### 项目 A\n开发 3 个模块");

        assertTrue(mention.contextSignature().contains("项目a"),
                () -> mention.contextSignature());
    }

    @Test
    void tableHeaderBecomesContextForDataRow() {
        List<NumericMention> mentions = extractor.extract(
                "| 模块数 | 接口数 |\n| --- | --- |\n| 3 | 5 |");

        assertEquals(2, mentions.size());
        assertTrue(mentions.get(0).contextSignature().contains("模块数接口数"),
                () -> mentions.get(0).contextSignature());
        assertEquals(mentions.get(0).contextSignature(), mentions.get(1).contextSignature());
    }

    @Test
    void onePointFiveWanIsParsedAsOneNumber() {
        NumericMention mention = only("用户数 一点五万");

        assertEquals("15000", mention.value());
        assertEquals("用户", mention.objectKey());
    }

    @Test
    void ambiguousChineseNumeralIsUnsupported() {
        NumericMention mention = only("处理约三千五条记录");

        assertEquals(Kind.UNSUPPORTED, mention.kind());
        assertFalse(mention.supported());
    }

    @Test
    void explicitChineseNumeralsKeepTheirExactValues() {
        assertEquals("3005", only("处理三千零五条记录").value());
        assertEquals("3500", only("处理三千五百条记录").value());
        assertEquals("15000", only("用户数 一万五千").value());
    }

    @Test
    void duoIsAnApproximationQualifier() {
        NumericMention mention = only("用户数 三千多");

        assertEquals("3000", mention.value());
        assertEquals(NumericMention.Qualifier.APPROX, mention.qualifier());
    }

    @Test
    void chineseRangeAndKSuffixAreUnsupported() {
        NumericMention range = only("预计三千至四千人");
        NumericMention k = only("处理 3.5k 条数据");

        assertEquals(Kind.UNSUPPORTED, range.kind());
        assertEquals(Kind.UNSUPPORTED, k.kind());
        assertFalse(range.supported());
        assertFalse(k.supported());
    }

    private NumericMention only(String text) {
        List<NumericMention> mentions = extractor.extract(text);
        assertEquals(1, mentions.size(), () -> "extracted " + mentions);
        assertNotNull(mentions.get(0));
        return mentions.get(0);
    }
}
