package com.careerpilot.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具返回值信封（《Agent设计》§4.2 第 6 条：只返回适合模型阅读的精简结果，
 * 不把完整简历原文再次传给模型）。
 *
 * <p>统一格式——工具方法一律返回 JSON 字符串：
 * <pre>
 * 成功：{"status":"SUCCESS","summary":"一句话结论","data":{…精简投影…}}
 * 失败：{"status":"FAILED","error":"错误标识","summary":"失败说明"}
 * </pre>
 *
 * <p>{@code summary} 同时是落库载荷与越限部分结果文案的来源；
 * {@code data} 的体积由各工具自行控制（列表 ≤10 项、长文本 ≤500 字符）。
 */
public final class AgentToolPayloads {

    public static final String STATUS_SUCCESS = RecordedToolCall.STATUS_SUCCESS;

    public static final String STATUS_FAILED = RecordedToolCall.STATUS_FAILED;

    /** 达到单次请求工具调用次数上限，该次工具未执行 */
    public static final String ERROR_LIMIT_EXCEEDED = "TOOL_LIMIT_EXCEEDED";

    /** 达到单次对话总时长上限，该次工具未执行 */
    public static final String ERROR_TIME_EXHAUSTED = "TIME_LIMIT_EXCEEDED";

    /** 工具执行抛异常（归属校验失败、下游 502 等） */
    public static final String ERROR_TOOL_FAILED = "TOOL_EXECUTION_FAILED";

    /** summary 字段的长度上限（《Agent设计》§6.4 的文本口径；极小，仅作兜底） */
    private static final int SUMMARY_LIMIT = 200;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private AgentToolPayloads() {
    }

    /** 成功信封；{@code data} 为精简投影（Map/List 会自动序列化） */
    public static String success(String summary, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", STATUS_SUCCESS);
        payload.put("summary", limit(summary));
        payload.put("data", data);
        return write(payload);
    }

    /** 失败信封；{@code error} 取本类常量或业务错误标识 */
    public static String failure(String error, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", STATUS_FAILED);
        payload.put("error", error);
        payload.put("summary", limit(summary));
        return write(payload);
    }

    /**
     * 从工具返回的信封中提取 summary（落库载荷与部分结果文案用）。
     * 返回非 JSON（理论不会发生）时退化为截断后的原文。
     */
    public static String summaryOf(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "(无结果)";
        }
        try {
            Map<?, ?> parsed = MAPPER.readValue(toolResult, Map.class);
            Object summary = parsed.get("summary");
            if (summary instanceof String text && !text.isBlank()) {
                return limit(text);
            }
        }
        catch (RuntimeException ex) {
            // 不视为错误：记录原文摘要即可
        }
        return limit(toolResult);
    }

    /**
     * 从工具返回的信封中解析 status（工具方法返回失败信封但未抛异常时，
     * 记录应按 FAILED 计）。解析失败按 SUCCESS 处理（工具正常返回即为成功）。
     */
    public static String statusOf(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return STATUS_SUCCESS;
        }
        try {
            Object status = MAPPER.readValue(toolResult, Map.class).get("status");
            if (STATUS_FAILED.equals(status)) {
                return STATUS_FAILED;
            }
        }
        catch (RuntimeException ex) {
            // 非 JSON 返回值：视为成功（本项目的工具一律返回信封，这是防御性分支）
        }
        return STATUS_SUCCESS;
    }

    private static String write(Map<String, Object> payload) {
        return MAPPER.writeValueAsString(payload);
    }

    private static String limit(String text) {
        if (text == null) {
            return "(无说明)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > SUMMARY_LIMIT ? flat.substring(0, SUMMARY_LIMIT) + "…" : flat;
    }

}
