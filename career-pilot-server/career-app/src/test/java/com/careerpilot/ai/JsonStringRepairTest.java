package com.careerpilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 未转义引号本地修复（M14）：可机械修复的坏 JSON 能救回、正常内容逐字符不变、
 * 已转义内容不被重复转义。
 *
 * 断言用真实 Jackson 解析验证"修复后可读"，而不是字符串比对——修复的目标即是可解析。
 */
class JsonStringRepairTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("字符串内未转义的引号被补上反斜杠且修复后可解析")
    void repairsUnescapedQuotes() throws Exception {
        String broken = "{\"analysis\": \"他说\"很好\"然后继续\", \"score\": 80}";
        // 修复前不可解析
        boolean beforeRepairFailed = false;
        try {
            objectMapper.readValue(broken, Map.class);
        }
        catch (Exception ex) {
            beforeRepairFailed = true;
        }
        assertTrue(beforeRepairFailed, "前置：原始文本应无法解析");

        String repaired = JsonStringRepair.repairUnescapedQuotes(broken);
        Map<?, ?> parsed = objectMapper.readValue(repaired, Map.class);
        assertEquals("他说\"很好\"然后继续", parsed.get("analysis"));
        assertEquals(80, parsed.get("score"));
    }

    @Test
    @DisplayName("字符串内裸制表符/换行被转义且修复后可解析")
    void repairsUnescapedControlCharacters() throws Exception {
        String broken = "{\"content\": \"1. \t参与接口开发\n2. 测试\", \"n\": 1}";
        boolean beforeRepairFailed = false;
        try {
            objectMapper.readValue(broken, Map.class);
        }
        catch (Exception ex) {
            beforeRepairFailed = true;
        }
        assertTrue(beforeRepairFailed, "前置：含裸控制字符的文本应无法解析");

        String repaired = JsonStringRepair.repairUnescapedQuotes(broken);
        Map<?, ?> parsed = objectMapper.readValue(repaired, Map.class);
        assertEquals("1. \t参与接口开发\n2. 测试", parsed.get("content"));
        assertEquals(1, parsed.get("n"));
    }

    @Test
    @DisplayName("正常 JSON 逐字符不变")
    void keepsValidJsonUnchanged() {
        String valid = "{\"a\": \"x\", \"nested\": {\"b\": [1, 2]}, \"n\": null}";
        assertEquals(valid, JsonStringRepair.repairUnescapedQuotes(valid));
    }

    @Test
    @DisplayName("已转义的引号不被重复转义")
    void keepsEscapedQuotesUnchanged() {
        String valid = "{\"a\": \"他说\\\"很好\\\"\", \"b\": 1}";
        assertEquals(valid, JsonStringRepair.repairUnescapedQuotes(valid));
    }

    @Test
    @DisplayName("字符串以内容引号结尾时（后接 } 或末尾）视为结束引号")
    void treatsTrailingQuoteBeforeBraceAsTerminator() throws Exception {
        String valid = "{\"a\": \"hello\"}";
        assertEquals(valid, JsonStringRepair.repairUnescapedQuotes(valid));
        objectMapper.readValue(JsonStringRepair.repairUnescapedQuotes(valid), Map.class);
    }

    @Test
    @DisplayName("空内容与 null 原样返回")
    void returnsBlankAsIs() {
        assertNull(JsonStringRepair.repairUnescapedQuotes(null));
        assertEquals("", JsonStringRepair.repairUnescapedQuotes(""));
        assertEquals("   ", JsonStringRepair.repairUnescapedQuotes("   "));
    }

}
