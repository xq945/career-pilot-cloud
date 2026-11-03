package com.careerpilot.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeDetail;
import com.careerpilot.dto.ResumeFile;
import com.careerpilot.dto.ResumeUploadRequest;
import com.careerpilot.dto.ResumeUploadResponse;
import com.careerpilot.dto.ResumeView;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.service.storage.FileStorageService;

/**
 * 简历服务（《API接口设计》4.1–4.4）。
 *
 * 文件存储细节委托 {@link FileStorageService}（M2 建立的本地/OSS 双实现设施），
 * 文本提取委托 {@link TextExtractionService}；本类负责业务规则：
 *
 * - **三重校验**（扩展名 + MIME + 内容特征，任一不符 415）：见 {@link #resolveFormat}；
 * - 单文件 10 MB 上限（超限 413，容器层另有一道 10 MB 限制）；
 * - 提取失败/为空/过短拒绝入库（400），且**已落盘的文件会被清理**——不留孤儿文件；
 * - 文本截断 20000 字符（由 TextExtractionService 完成）；
 * - 归属校验：所有查询带 user_id，查不到一律 404（不区分"不存在"与"他人"）；
 * - 逻辑删除手写 status 条件（《数据库设计》5.4，不用 @TableLogic）。
 *
 * 写路径约定：上传/删除**不加 @Transactional**——文件不是事务资源，
 * 顺序固定为「写文件 → INSERT（自动提交）」与「UPDATE（自动提交）→ 删文件」，
 * DB 失败时补偿删除文件，删文件失败不阻塞（与 UserService 同法）。
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    /** 单文件上限（《系统架构设计》6.1；容器层同时配置了 10 MB） */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** 三重校验读取的文件头长度：需覆盖 HTML 特征标签的探测范围 */
    private static final int HEADER_LENGTH = 8 * 1024;

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** PDF 魔数 %PDF- */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * DOCX 是 ZIP 容器，魔数为 PK\x03\x04。
     * **该特征无法区分 DOCX 与 xlsx/pptx/普通 zip**，由扩展名与 MIME 限定，
     * 属已知限制（《API接口设计》4.1 已明文记录）。
     */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    /** DOC 为 OLE2 复合文档，魔数 D0 CF 11 E0 A1 B1 1A E1（同样无法区分 xls/ppt） */
    private static final byte[] OLE2_MAGIC =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /** HTML 特征标签（不区分大小写；文本类格式无标准魔数，以此替代"魔数"校验） */
    private static final Pattern HTML_FEATURE = Pattern.compile(
            "<(?:!doctype|html|head|body|title|meta|div|p|span|h[1-6]|table|ul|ol|li|a|br|section|article)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ResumeMapper resumeMapper;

    private final ResumeAnalysisMapper resumeAnalysisMapper;

    private final FileStorageService fileStorageService;

    private final TextExtractionService textExtractionService;

    public ResumeService(ResumeMapper resumeMapper, ResumeAnalysisMapper resumeAnalysisMapper,
                         FileStorageService fileStorageService,
                         TextExtractionService textExtractionService) {
        this.resumeMapper = resumeMapper;
        this.resumeAnalysisMapper = resumeAnalysisMapper;
        this.fileStorageService = fileStorageService;
        this.textExtractionService = textExtractionService;
    }

    /**
     * 上传简历（4.1）：校验 → 存文件 → 提取文本 → 入库。
     *
     * 提取失败时删除已落盘文件并整体失败（400），不产生半成品记录。
     */
    public ResumeUploadResponse upload(Long userId, ResumeUploadRequest request, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的简历文件");
        }
        // 先大小后类型：超限文件不必读取内容
        if (file.getSize() > MAX_FILE_BYTES) {
            throw BusinessException.payloadTooLarge("简历文件不能超过 10 MB");
        }
        FileFormat format = resolveFormat(file);
        String originalFileName = file.getOriginalFilename();

        String storageKey = fileStorageService.store(userId, file, format.extension());
        String content;
        try {
            content = textExtractionService.extract(file);
        }
        catch (RuntimeException ex) {
            // 提取失败：整体失败，清理刚落盘的文件（不留孤儿）
            fileStorageService.deleteQuietly(storageKey);
            throw ex;
        }

        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setName(resolveName(request.getName(), originalFileName));
        resume.setFileName(originalFileName);
        resume.setFileType(format.type());
        resume.setFilePath(storageKey);
        resume.setContent(content);
        resume.setStatus(Resume.STATUS_ACTIVE);
        // 显式设置创建时间：MP 的 insert 不会回读数据库默认值，
        // 否则响应中的 createdAt 为空
        resume.setCreatedAt(LocalDateTime.now());
        try {
            resumeMapper.insert(resume);
        }
        catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storageKey);
            throw ex;
        }
        return toUploadResponse(resume);
    }

    /** 列表（4.2）：仅本人有效简历，按 created_at 倒序（id 倒序作稳定排序的 tie-breaker） */
    public PageResult<ResumeView> list(Long userId, PageQuery query) {
        Page<Resume> page = new Page<>(query.getPage(), query.getSize());
        IPage<Resume> result = resumeMapper.selectPage(page, Wrappers.<Resume>lambdaQuery()
                .eq(Resume::getUserId, userId)
                .eq(Resume::getStatus, Resume.STATUS_ACTIVE)
                .orderByDesc(Resume::getCreatedAt)
                .orderByDesc(Resume::getId));

        Set<Long> analyzedIds = analyzedResumeIds(result.getRecords());
        List<ResumeView> records = result.getRecords().stream()
                .map(resume -> toView(resume, analyzedIds.contains(resume.getId())))
                .toList();
        return PageResult.of(result, records);
    }

    /** 详情（4.3）：仅元数据，不返回提取文本与存储路径 */
    public ResumeDetail detail(Long userId, Long id) {
        Resume resume = getOwnedResume(userId, id);
        ResumeDetail detail = new ResumeDetail();
        detail.setId(resume.getId());
        detail.setName(resume.getName());
        detail.setFileName(resume.getFileName());
        detail.setFileType(resume.getFileType());
        detail.setCreatedAt(resume.getCreatedAt());
        detail.setAnalyzed(isAnalyzed(resume.getId()));
        return detail;
    }

    /**
     * 删除（4.4）：逻辑删除 + 同步物理删除文件。
     *
     * 三种情况定死：本人有效简历 → 删除成功；重复删除（已 status=0）→ 幂等成功；
     * 不存在或属于他人 → 404。
     */
    public void delete(Long userId, Long id) {
        // 刻意不过滤 status：已逻辑删除的行仍可查到，据此实现幂等
        Resume resume = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId));
        if (resume == null) {
            throw BusinessException.notFound("简历不存在");
        }
        if (resume.getStatus() != null && resume.getStatus() == Resume.STATUS_DELETED) {
            return;
        }
        resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId)
                .eq(Resume::getStatus, Resume.STATUS_ACTIVE)
                .set(Resume::getStatus, Resume.STATUS_DELETED));
        // 先改库后删文件：删文件失败不阻塞（历史记录保留、文件成为孤儿属可接受的降级）
        fileStorageService.deleteQuietly(resume.getFilePath());
    }

    /**
     * 读取简历文件内容（4.7）：归属校验通过后交给存储层读出。
     *
     * 简历原文按**私有文件**处理——不产生公开地址（不走 publicUrl / `/uploads/**`），
     * 由 Controller 组装响应头后经鉴权接口下发。
     */
    public ResumeFile readFile(Long userId, Long id) {
        Resume resume = getOwnedResume(userId, id);
        return new ResumeFile(fileStorageService.read(resume.getFilePath()),
                resume.getFileName(), resume.getFileType());
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 归属校验：查不到（不存在或属于他人）一律 404，不区分二者以避免暴露资源存在性。
     *
     * M4 起对外公开：简历分析（{@link ResumeAnalysisService}）与 M8 的 Agent 工具
     * 复用同一条归属校验路径（《Agent设计》4.1 示意代码即调用本方法）。
     */
    public Resume getOwnedResume(Long userId, Long id) {
        Resume resume = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId)
                .eq(Resume::getStatus, Resume.STATUS_ACTIVE));
        if (resume == null) {
            throw BusinessException.notFound("简历不存在");
        }
        return resume;
    }

    /** 该简历是否已有至少一条成功分析记录（`analyzed` 的判定，4.2/4.3 同义） */
    private boolean isAnalyzed(Long resumeId) {
        Long count = resumeAnalysisMapper.selectCount(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId)
                .eq(ResumeAnalysis::getStatus, ResumeAnalysis.STATUS_SUCCESS));
        return count != null && count > 0;
    }

    /**
     * 批量查询"已有成功分析"的简历 ID（列表页拼 analyzed 用）。
     *
     * 只对**当页** ID 做一次 IN 查询（两次查询、无自定义 SQL、无 N+1）。
     */
    private Set<Long> analyzedResumeIds(List<Resume> resumes) {
        List<Long> ids = resumes.stream().map(Resume::getId).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return resumeAnalysisMapper.selectList(Wrappers.<ResumeAnalysis>lambdaQuery()
                        .select(ResumeAnalysis::getResumeId)
                        .in(ResumeAnalysis::getResumeId, ids)
                        .eq(ResumeAnalysis::getStatus, ResumeAnalysis.STATUS_SUCCESS))
                .stream()
                .map(ResumeAnalysis::getResumeId)
                .collect(Collectors.toSet());
    }

    /** 简历名称：优先显式传入；缺省取原文件名，超 100 字符截断 */
    private String resolveName(String requestedName, String originalFileName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        String fallback = originalFileName == null || originalFileName.isBlank()
                ? "未命名简历"
                : originalFileName;
        return fallback.length() > 100 ? fallback.substring(0, 100) : fallback;
    }

    private ResumeView toView(Resume resume, boolean analyzed) {
        ResumeView view = new ResumeView();
        view.setId(resume.getId());
        view.setName(resume.getName());
        view.setFileName(resume.getFileName());
        view.setFileType(resume.getFileType());
        view.setCreatedAt(resume.getCreatedAt());
        view.setAnalyzed(analyzed);
        return view;
    }

    private ResumeUploadResponse toUploadResponse(Resume resume) {
        ResumeUploadResponse response = new ResumeUploadResponse();
        response.setId(resume.getId());
        response.setName(resume.getName());
        response.setFileName(resume.getFileName());
        response.setFileType(resume.getFileType());
        response.setTextExtracted(true);
        response.setCreatedAt(resume.getCreatedAt());
        return response;
    }

    // ---------------------------------------------------------------- 三重校验

    /**
     * 扩展名 + MIME + 内容特征三重校验，三者须互相一致；返回规范化格式。
     *
     * 详细规则见《API接口设计》4.1 的类型对照表。**JPG/PNG 图片简历本版不支持**
     * （需 OCR，记 TODO），在此自然落入 415。
     */
    private FileFormat resolveFormat(MultipartFile file) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        extension = extension == null ? "" : extension.toLowerCase();
        String mimeType = normalizeMime(file.getContentType());
        byte[] header = readHeader(file);

        if ("pdf".equals(extension) && "application/pdf".equals(mimeType)
                && startsWith(header, PDF_MAGIC)) {
            return FileFormat.PDF;
        }
        if ("docx".equals(extension) && DOCX_MIME.equals(mimeType)
                && startsWith(header, ZIP_MAGIC)) {
            return FileFormat.DOCX;
        }
        if ("doc".equals(extension) && "application/msword".equals(mimeType)
                && startsWith(header, OLE2_MAGIC)) {
            return FileFormat.DOC;
        }
        if (("html".equals(extension) || "htm".equals(extension)) && "text/html".equals(mimeType)
                && looksLikeHtml(header)) {
            // .htm 与 .html 同属 HTML，落盘统一归一为 html（同 M2 头像 jpeg→jpg 的做法）
            return FileFormat.HTML;
        }
        if ("txt".equals(extension) && "text/plain".equals(mimeType)
                && looksLikeText(header)) {
            return FileFormat.TXT;
        }
        throw BusinessException.unsupportedMediaType(
                "文件类型不支持，仅支持 PDF、DOCX、DOC、HTML、TXT 格式");
    }

    /**
     * 剥离 MIME 参数（如 {@code text/plain;charset=utf-8}）并统一小写；
     * multipart 部件带参数时直接比较会误判为"类型不符"。
     */
    private String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int semicolon = mimeType.indexOf(';');
        String base = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return base.trim().toLowerCase();
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEADER_LENGTH);
        }
        catch (IOException ex) {
            log.warn("读取上传文件头失败");
            throw BusinessException.unsupportedMediaType("文件内容无法识别");
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 文本内容判定：无 NUL 字节即视为文本。
     *
     * 不强制 UTF-8——中文 TXT 常为 GBK，编码检测交给 Tika；
     * 二进制文件（可执行文件、压缩包等）通常在前 8 KB 内即出现 NUL 字节。
     */
    private static boolean looksLikeText(byte[] header) {
        for (byte b : header) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeHtml(byte[] header) {
        if (!looksLikeText(header)) {
            return false;
        }
        // 标签是 ASCII，用 ISO-8859-1 无损转换即可（避免按错误编码解码中文导致异常）
        String text = new String(header, StandardCharsets.ISO_8859_1);
        return HTML_FEATURE.matcher(text).find();
    }

    /** 支持的简历文件格式：规范化扩展名（落盘用）与类型名（入库/响应用） */
    private enum FileFormat {

        PDF("pdf", "PDF"),
        DOCX("docx", "DOCX"),
        DOC("doc", "DOC"),
        HTML("html", "HTML"),
        TXT("txt", "TXT");

        private final String extension;

        private final String type;

        FileFormat(String extension, String type) {
            this.extension = extension;
            this.type = type;
        }

        String extension() {
            return extension;
        }

        String type() {
            return type;
        }

    }

}
