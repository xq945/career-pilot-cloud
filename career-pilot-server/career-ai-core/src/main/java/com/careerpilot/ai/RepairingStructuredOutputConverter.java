package com.careerpilot.ai;

import org.slf4j.Logger;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * 带"本地修复"能力的结构化输出转换器（M14）。
 *
 * <p>装饰 Spring AI 的 {@link BeanOutputConverter}：先按原样解析；失败时对**未转义引号**
 * 做一次本地修复后重试。修复后仍失败则抛出**原始异常**——保证 {@link AiCallTemplate} 的
 * 异常翻译（50201）与修复型重试的触发条件与未包装时完全一致。
 *
 * <p>设计依据（《系统架构设计》§11 V8）：`responseEntity` 的格式说明经 request context
 * 注入、{@code convert()} 在框架调用链内执行——因此**只装饰转换器**即可插入本地修复，
 * 无需改动框架的调用与注入路径（零行为等价性风险）。
 *
 * @param <T> 目标输出类型
 */
class RepairingStructuredOutputConverter<T> implements StructuredOutputConverter<T> {

    private final BeanOutputConverter<T> delegate;

    private final String scene;

    private final Logger log;

    RepairingStructuredOutputConverter(BeanOutputConverter<T> delegate, String scene, Logger log) {
        this.delegate = delegate;
        this.scene = scene;
        this.log = log;
    }

    @Override
    public String getFormat() {
        return delegate.getFormat();
    }

    @Override
    public String getJsonSchema() {
        return delegate.getJsonSchema();
    }

    @Override
    public T convert(String text) {
        try {
            return delegate.convert(text);
        }
        catch (RuntimeException firstError) {
            String repaired = JsonStringRepair.repairUnescapedQuotes(text);
            if (!repaired.equals(text)) {
                try {
                    T result = delegate.convert(repaired);
                    // 只记场景与长度，不落任何模型输出内容（《Agent设计》§8 脱敏约定）
                    log.warn("AI 输出修复：未转义引号/控制字符已本地修复 scene={} 原文长度={} 修复后长度={}",
                            scene, text.length(), repaired.length());
                    return result;
                }
                catch (RuntimeException repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

}
