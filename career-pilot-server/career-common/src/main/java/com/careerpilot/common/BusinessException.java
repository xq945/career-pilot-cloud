package com.careerpilot.common;

/**
 * 业务异常：同时携带业务错误码与 HTTP 状态码。
 *
 * 业务码定义见《API接口设计》第 1 节，避免在业务代码中散落魔法数字。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    private final int httpStatus;

    /**
     * 可选的附加响应数据（M16 修复 O-16）：同步降级执行失败时携带 optimizationId，
     * 让前端能区分“任务已创建但执行失败”与“任务尚未创建”。
     * 其余场景为 null，错误响应与既有契约逐字节一致。
     */
    private final Object data;

    public BusinessException(int code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public BusinessException(int code, int httpStatus, String message, Object data) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    /** 400 / 40000 通用参数错误 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(40000, 400, message);
    }

    /**
     * 400 / 40002 图形验证码错误或已失效（M18）。
     *
     * 输错、过期、重复使用、标识缺失/不存在一律用本码与**同一文案**——不向调用方区分
     * "输错了"与"已失效"，与登录接口不区分"账号不存在"与"密码错误"的口径一致
     * （《API接口设计》§1/A01）。前端只需一套处理逻辑：提示 + 刷新验证码。
     */
    public static BusinessException captchaInvalid(String message) {
        return new BusinessException(40002, 400, message);
    }

    /** 401 / 40100 未登录或 Token 无效 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(40100, 401, message);
    }

    /**
     * 403 / 40300 账号状态受限（当前仅用于"登录时账号已被禁用"）。
     *
     * **资源级越权不要用本方法**——归属校验失败一律 404（不区分"不存在"与"无权访问"，
     * 避免暴露资源存在性，见《API接口设计》§1/A01）；Security 层的授权失败出口同理
     * （见 SecurityConfig 的 accessDeniedHandler）。
     */
    public static BusinessException forbidden(String message) {
        return new BusinessException(40300, 403, message);
    }

    /** 404 / 40400 资源不存在 */
    public static BusinessException notFound(String message) {
        return new BusinessException(40400, 404, message);
    }

    /** 413 / 41300 文件过大 */
    public static BusinessException payloadTooLarge(String message) {
        return new BusinessException(41300, 413, message);
    }

    /** 415 / 41500 文件类型不支持 */
    public static BusinessException unsupportedMediaType(String message) {
        return new BusinessException(41500, 415, message);
    }

    /**
     * 502 / 50200 大模型服务暂不可用（超时、网络故障、上游 HTTP 错误；可重试）。
     *
     * 抛出后不写入任何分析记录（《数据库设计》5.3：第一版只保存成功结果）。
     */
    public static BusinessException aiUnavailable(String message) {
        return new BusinessException(50200, 502, message);
    }

    /** 502 / 50201 大模型输出无法解析（未保存任何结果，可重试） */
    public static BusinessException aiInvalidOutput(String message) {
        return new BusinessException(50201, 502, message);
    }

    /**
     * 500 / 50000 服务端内部错误（M14 新增，当前用于模型服务 API Key 的加解密失败：
     * 加密主密钥变更等配置问题，属于服务端状态而非客户端请求错误）。
     *
     * 消息面向用户可读，但**不得包含明文 Key、密文或异常细节**（《系统架构设计》§7.4）。
     */
    public static BusinessException internalError(String message) {
        return new BusinessException(50000, 500, message);
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 附加响应数据；null 表示沿用既有错误响应结构 */
    public Object getData() {
        return data;
    }

}
