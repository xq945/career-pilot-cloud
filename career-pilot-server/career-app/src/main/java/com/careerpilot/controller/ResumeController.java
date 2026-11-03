package com.careerpilot.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.dto.ResumeDetail;
import com.careerpilot.dto.ResumeFile;
import com.careerpilot.dto.ResumeUploadRequest;
import com.careerpilot.dto.ResumeUploadResponse;
import com.careerpilot.dto.ResumeView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

import jakarta.validation.Valid;

/**
 * 简历接口（《API接口设计》4.1–4.6）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId（《系统架构设计》4.1）。
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    private final ResumeAnalysisService resumeAnalysisService;

    public ResumeController(ResumeService resumeService, ResumeAnalysisService resumeAnalysisService) {
        this.resumeService = resumeService;
        this.resumeAnalysisService = resumeAnalysisService;
    }

    /**
     * 上传简历（4.1）。
     *
     * - {@code file} 的 required = false 是为了让「缺 file 字段」走到业务层判空并返回
     *   40000，而不是抛 MissingServletRequestPartException 落到 50000（同 UserController）；
     * - {@code name} 经 {@link ResumeUploadRequest} 走 Bean Validation（超 100 字符 → 40001
     *   且 data 携带字段级提示）。
     */
    @PostMapping
    public ApiResponse<ResumeUploadResponse> upload(
            @Valid @ModelAttribute ResumeUploadRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(resumeService.upload(CurrentUser.requireId(), request, file));
    }

    /** 简历列表（4.2）：分页参数越界返回 40001 */
    @GetMapping
    public ApiResponse<PageResult<ResumeView>> list(@Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(resumeService.list(CurrentUser.requireId(), query));
    }

    /** 简历详情（4.3）：不含提取文本与存储路径 */
    @GetMapping("/{id}")
    public ApiResponse<ResumeDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(resumeService.detail(CurrentUser.requireId(), id));
    }

    /** 删除简历（4.4）：逻辑删除 + 物理删除文件；重复删除幂等成功 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resumeService.delete(CurrentUser.requireId(), id);
        return ApiResponse.success();
    }

    /**
     * 发起简历分析（4.5）：无请求体；同步调用通常 10–30 秒（前端超时按 AI 接口约定设 120 秒）。
     *
     * 简历内容未变化时直接复用已有结果（不重复调用模型）；模型超时/不可用 → 50200、
     * 输出无法解析 → 50201，失败不写入任何分析记录。
     */
    @PostMapping("/{id}/analyze")
    public ApiResponse<ResumeAnalysisDetail> analyze(@PathVariable Long id) {
        return ApiResponse.success(resumeAnalysisService.analyze(CurrentUser.requireId(), id));
    }

    /** 获取简历分析（4.6）：返回最新一版成功分析；无结果时 HTTP 200 + analyzed=false */
    @GetMapping("/{id}/analysis")
    public ApiResponse<ResumeAnalysisView> analysis(@PathVariable Long id) {
        return ApiResponse.success(resumeAnalysisService.getLatest(CurrentUser.requireId(), id));
    }

    /**
     * 获取简历文件（4.7）：预览或下载**原文件**。
     *
     * 响应体是文件二进制——全项目唯一的非 JSON 成功响应；失败时仍是统一结构的 JSON。
     * 原文按**私有文件**处理：经 JWT 鉴权与归属校验（失败一律 404），不经 `/uploads/**` 静态路径暴露。
     *
     * @param download true 时一律以附件下发；false（缺省）时可在浏览器内渲染的格式用 inline
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean download) {
        ResumeFile file = resumeService.readFile(CurrentUser.requireId(), id);
        boolean attachment = download || !isPreviewable(file.fileType());
        ContentDisposition disposition = ContentDisposition
                .builder(attachment ? "attachment" : "inline")
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(file.fileType()))
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }

    /** 浏览器能否内嵌渲染：PDF / TXT / HTML 可以；DOCX / DOC 无原生渲染能力（预览等价于下载） */
    private static boolean isPreviewable(String fileType) {
        return !("DOCX".equals(fileType) || "DOC".equals(fileType));
    }

    /**
     * 文件类型 → 响应 Content-Type。
     *
     * **HTML 刻意按 text/plain 下发**：以 text/html 渲染会让上传文件中的脚本在**后端同源**下
     * 执行（可读取 localStorage 中的 JWT），构成同源 XSS（《API接口设计》4.7）。
     */
    private static MediaType mediaTypeOf(String fileType) {
        if (fileType == null) {
            return new MediaType("text", "plain", StandardCharsets.UTF_8);
        }
        return switch (fileType) {
            case "PDF" -> MediaType.APPLICATION_PDF;
            case "DOCX" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "DOC" -> MediaType.parseMediaType("application/msword");
            default -> new MediaType("text", "plain", StandardCharsets.UTF_8);
        };
    }

}
