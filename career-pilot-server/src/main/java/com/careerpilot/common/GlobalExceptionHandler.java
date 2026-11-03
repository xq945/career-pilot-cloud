package com.careerpilot.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理，统一响应结构见《API接口设计》第 1 节。
 *
 * 约定：响应中不出现异常堆栈、API Key、SQL 或本机路径。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：按异常自带的 HTTP 状态与业务码返回。
     * {@code data} 为 null 时响应与既有契约完全一致；非 null 时为向后兼容的附加信息
     * （当前仅同步降级失败携带 optimizationId，见 O-16）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getData()));
    }

    /** Bean Validation 校验失败：返回 40001，data 中携带字段级信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(400)
                .body(ApiResponse.error(40001, "请求参数不合法", fieldErrors));
    }

    /** 请求体无法解析（非法 JSON / 非 UTF-8 字节）：属"参数不合法"，按《API接口设计》1 节返回 40000 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error(40000, "请求体格式不正确"));
    }

    /** 容器层上传超限（参数解析阶段抛出）：默认会落到 50000，按《API接口设计》1 节映射为 413/41300 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过容器限制");
        return ResponseEntity.status(413).body(ApiResponse.error(41300, "上传文件过大"));
    }

    /** 静态资源或未匹配路径不存在：默认会落到 50000，映射为 404/40400 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404).body(ApiResponse.error(40400, "资源不存在"));
    }

    /** 未预期异常：记录完整堆栈，但只向调用方返回概括信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(500).body(ApiResponse.error(50000, "服务内部错误"));
    }

}
