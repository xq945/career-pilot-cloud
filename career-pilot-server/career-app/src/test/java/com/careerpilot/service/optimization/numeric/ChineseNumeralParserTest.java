package com.careerpilot.service.optimization.numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChineseNumeralParserTest {

    @Test
    void parsesCommonIntegers() {
        assertEquals(new BigDecimal("3"), parse("三"));
        assertEquals(new BigDecimal("10"), parse("十"));
        assertEquals(new BigDecimal("15"), parse("十五"));
        assertEquals(new BigDecimal("20"), parse("二十"));
        assertEquals(new BigDecimal("105"), parse("一百零五"));
        assertEquals(new BigDecimal("2000"), parse("两千"));
        assertEquals(new BigDecimal("300000"), parse("三十万"));
    }

    @Test
    void parsesDecimal() {
        assertEquals(new BigDecimal("1.5"), parse("一点五"));
    }

    @Test
    void parsesDigitByDigitYear() {
        assertEquals(Optional.of(2025), ChineseNumeralParser.parseDigitYear("二〇二五"));
        assertEquals(Optional.of(2025), ChineseNumeralParser.parseDigitYear("二零二五"));
        assertTrue(ChineseNumeralParser.parseDigitYear("二十五").isEmpty());
    }

    @Test
    void rejectsNonNumericText() {
        assertTrue(ChineseNumeralParser.parse("三分之一").isEmpty());
        assertTrue(ChineseNumeralParser.parse("若干").isEmpty());
        assertTrue(ChineseNumeralParser.parse("abc").isEmpty());
    }

    @Test
    void detectsAmbiguousShorthand() {
        assertTrue(ChineseNumeralParser.isAmbiguousShorthand("三千五"));
        assertTrue(ChineseNumeralParser.isAmbiguousShorthand("一万五"));
        assertTrue(ChineseNumeralParser.isAmbiguousShorthand("一百五"));
        assertTrue(ChineseNumeralParser.isAmbiguousShorthand("两万三"));
    }

    @Test
    void explicitChineseNumeralsAreNotAmbiguous() {
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("三千零五"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("三千五百"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("十五"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("二十五"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("一万五千"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("三万"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("一点五"));
        assertFalse(ChineseNumeralParser.isAmbiguousShorthand("三千"));
    }

    private BigDecimal parse(String value) {
        return ChineseNumeralParser.parse(value).orElseThrow();
    }
}
