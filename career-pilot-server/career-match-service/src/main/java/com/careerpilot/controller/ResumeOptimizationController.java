package com.careerpilot.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.OptimizedResumeFile;
import com.careerpilot.dto.ResumeOptimizationContentRequest;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.ResumeOptimizationService;
import com.careerpilot.service.export.ResumeDocxExporter;

import jakarta.validation.Valid;

/**
 * 简历定向优化接口（《API接口设计》§6A.1–§6A.6）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId（《系统架构设计》4.1）；
 * 归属校验失败一律 404（不区分"不存在"与"他人"）。
 */
@RestController
@RequestMapping("/api/optimizations")
public class ResumeOptimizationController {

    /** 导出响应的 Content-Type（全项目第二个非 JSON 成功响应，同 §4.7 的简历原文下载） */
    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ResumeOptimizationService optimizationService;

    private final ResumeDocxExporter docxExporter;

    public ResumeOptimizationController(ResumeOptimizationService optimizationService,
                                        ResumeDocxExporter docxExporter) {
        this.optimizationService = optimizationService;
        this.docxExporter = docxExporter;
    }

    /**
     * 创建优化（6A.1）：**每次调用创建一条新记录**（同一简历+岗位的 versionNo 递增）。
     * 缺分析或岗位分析过期 → 400；模型超时/输出异常 → 502，失败不落优化记录。
     *
     * 同步调用通常耗时 25–80 秒（匹配解释 + 优化生成共 2 次模型调用），前端超时按 300 秒设置。
     */
    @PostMapping
    public ApiResponse<ResumeOptimizationDetail> create(
            @Valid @RequestBody ResumeOptimizationCreateRequest request) {
        return ApiResponse.success(optimizationService.create(CurrentUser.requireId(), request));
    }

    /** 优化记录列表（6A.2）：created_at 倒序分页；内联资源名称与优化前后分数 */
    @GetMapping
    public ApiResponse<PageResult<ResumeOptimizationView>> list(@Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(optimizationService.list(CurrentUser.requireId(), query));
    }

    /** 优化详情（6A.3）：响应结构与创建接口一致；不存在或非本人 → 404 */
    @GetMapping("/{id}")
    public ApiResponse<ResumeOptimizationDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(optimizationService.detail(CurrentUser.requireId(), id));
    }

    /** 保存用户编辑稿（6A.4）：不覆盖模型原稿；旧稿进入编辑历史（上限 10 版） */
    @PutMapping("/{id}/content")
    public ApiResponse<ResumeOptimizationDetail> saveContent(
            @PathVariable Long id, @Valid @RequestBody ResumeOptimizationContentRequest request) {
        return ApiResponse.success(
                optimizationService.saveContent(CurrentUser.requireId(), id, request.getContent()));
    }

    /** 重新评估（6A.5）：对优化稿重新结构化分析后按同一规则重算；每次真重跑、不复用 */
    @PostMapping("/{id}/evaluate")
    public ApiResponse<ResumeOptimizationDetail> evaluate(@PathVariable Long id) {
        return ApiResponse.success(optimizationService.evaluate(CurrentUser.requireId(), id));
    }

    /**
     * 手动重试失败的任务（6A.7，M16）：仅 FAILED 可重试；重置为 PENDING 后重新入队
     * （入队失败降级为同步执行）；返回与详情接口一致的结构。
     */
    @PostMapping("/{id}/retry")
    public ApiResponse<ResumeOptimizationDetail> retry(@PathVariable Long id) {
        return ApiResponse.success(optimizationService.retry(CurrentUser.requireId(), id));
    }

    /**
     * 导出优化稿 DOCX（6A.6）：内容取用户编辑稿（为空时回落模型原稿）。
     *
     * 响应体是文件二进制（失败时仍是统一结构的 JSON）；**不落盘**，字节流直接下发。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        OptimizedResumeFile file = optimizationService.readExport(CurrentUser.requireId(), id);
        byte[] content = docxExporter.toDocx(file.getContent());
        ContentDisposition disposition = ContentDisposition.builder("attachment")
                .filename(file.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content);
    }

}
