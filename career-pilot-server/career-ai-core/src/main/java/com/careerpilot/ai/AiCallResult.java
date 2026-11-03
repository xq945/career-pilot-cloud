package com.careerpilot.ai;

/**
 * 一次成功的模型调用结果：结构化输出 + 成本核算元数据（《Agent设计》8 节）。
 *
 * <p>token 字段可能为 null（上游未返回 usage），使用方须判空。
 *
 * <p>{@code providerKey}（M14）为该次调用**实际钉住**的模型服务标识（null = 平台默认）：
 * 调用方据此写入会话/优化记录的快照列（《数据库设计》5.13）——快照只反映真实调用，
 * 不依赖调用前的二次解析。
 */
public record AiCallResult<T>(
        T value,
        String modelName,
        String providerKey,
        long elapsedMillis,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    /**
     * 便捷构造：providerKey 缺省（null = 平台默认）。
     * 供不关心模型服务来源的调用方与既有测试使用（M14 前只有 6 参形式）。
     */
    public AiCallResult(T value, String modelName, long elapsedMillis,
                        Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(value, modelName, null, elapsedMillis, promptTokens, completionTokens, totalTokens);
    }

}
