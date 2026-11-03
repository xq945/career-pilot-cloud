package com.careerpilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;

import tools.jackson.core.JacksonException;

/**
 * 带本地修复的结构化输出转换器（M14）：
 * 可修复的坏 JSON 直接救回（不抛异常、不额外调用模型）；不可修复时抛出**原始异常**，
 * 且异常链包含 {@link JacksonException}——这是 {@code AiCallTemplate} 判定 50201
 * 与触发修复型重试的前提（前提不成立时本测试失败，作为护栏）。
 */
class RepairingStructuredOutputConverterTest {

    /** 测试用输出类型（record，与项目 DTO 的 Jackson 反序列化路径一致） */
    record Sample(String analysis, int score) {
    }

    private final BeanOutputConverter<Sample> delegate = new BeanOutputConverter<>(Sample.class);

    private RepairingStructuredOutputConverter<Sample> converter;

    private RepairingStructuredOutputConverter<Sample> newConverter() {
        return new RepairingStructuredOutputConverter<>(delegate, "test",
                LoggerFactory.getLogger(RepairingStructuredOutputConverterTest.class));
    }

    @Test
    @DisplayName("正常 JSON 直接解析成功")
    void convertsValidJson() {
        converter = newConverter();
        Sample sample = converter.convert("{\"analysis\": \"不错\", \"score\": 80}");
        assertEquals("不错", sample.analysis());
        assertEquals(80, sample.score());
    }

    @Test
    @DisplayName("未转义引号的坏 JSON 被本地修复后解析成功")
    void repairsBrokenJson() {
        converter = newConverter();
        Sample sample = converter.convert("{\"analysis\": \"他说\"很好\"然后继续\", \"score\": 66}");
        assertEquals("他说\"很好\"然后继续", sample.analysis());
        assertEquals(66, sample.score());
    }

    @Test
    @DisplayName("不可修复的输出抛出 RuntimeException 且异常链含 JacksonException")
    void throwsOriginalErrorWhenUnrepairable() {
        converter = newConverter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> converter.convert("完全不是 JSON，也没有引号可修"));
        assertTrue(containsJacksonException(ex),
                "异常链需包含 JacksonException——AiCallTemplate 的 50201 判定与修复重试依赖它");
    }

    @Test
    @DisplayName("getFormat 与 getJsonSchema 委托原始转换器")
    void delegatesFormatAndSchema() {
        converter = newConverter();
        assertNotNull(converter.getFormat());
        assertFalse(converter.getFormat().isBlank());
        assertEquals(delegate.getJsonSchema(), converter.getJsonSchema());
    }

    private static boolean containsJacksonException(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof JacksonException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

}
