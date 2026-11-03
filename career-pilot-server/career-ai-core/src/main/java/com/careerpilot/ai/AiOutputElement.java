package com.careerpilot.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * AI 结构化输出的元素基类：承接"约定外字段"的丢弃与日志。
 *
 * 《Agent设计》6.4 要求「元素内出现约定外的字段**丢弃**」且「所有截断与丢弃均记脱敏日志」。
 * Jackson 对未映射字段默认静默忽略（Boot 4 的 Jackson 3 默认 FAIL_ON_UNKNOWN_PROPERTIES=false），
 * 静默即无法记日志——故基类用 {@code @JsonAnySetter} 把未知字段收进 unknownFields，
 * 由 {@link StructuredOutputSanitizer} 在清洗时取出、记日志（只记字段名）并清空。
 *
 * {@code @JsonIgnore} 保证该 Map 不参与序列化（落库/响应中不出现），
 * 且 Schema 生成器（jsonschema-generator）也不会把它列入 properties。
 */
public abstract class AiOutputElement {

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    /** Jackson 反序列化遇到未映射字段时回调（不注册属性，仅收集名称与值） */
    @JsonAnySetter
    public void putUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /** 取出并清空本次反序列化收集到的约定外字段（由清洗器调用） */
    @JsonIgnore
    public Map<String, Object> drainUnknownFields() {
        Map<String, Object> drained = new LinkedHashMap<>(unknownFields);
        unknownFields.clear();
        return drained;
    }

}
