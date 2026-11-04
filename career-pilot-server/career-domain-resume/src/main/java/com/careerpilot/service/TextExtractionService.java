package com.careerpilot.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import com.careerpilot.common.BusinessException;

/**
 * 文件文本提取（《系统架构设计》6.1、《API接口设计》4.1）。
 *
 * 用 Tika 的 AutoDetectParser 按内容自动选择解析器。提取失败、结果为空或过短
 * 一律视为"文件不含可复制文本"，返回 400——不产生半成品记录（上传整体失败）。
 *
 * 提取文本的上限截断在此统一完成：入库文本即"截断后的文本"，
 * 后续 AI 分析的 source_hash 必须基于**同一份截断文本**计算（《数据库设计》5.2）。
 */
@Service
@ConditionalOnProperty(prefix = "app.resume", name = "mode", havingValue = "local", matchIfMissing = true)
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** 提取文本入库上限（《API接口设计》§10、数据库设计 5.2） */
    public static final int MAX_CONTENT_LENGTH = 20000;

    /**
     * "过短"的判定阈值。设计文档只说"为空/过短"未给具体值（M3 实现时定），
     * 取 50：正常简历不可能只有几十个字符，低于此值基本是扫描件/图片/损坏文件。
     */
    private static final int MIN_CONTENT_LENGTH = 50;

    private static final String EXTRACT_FAILED_MESSAGE =
            "未能从文件中提取文字，请确认文件包含可复制的文本（扫描件与纯图片暂不支持）";

    /**
     * AutoDetectParser 线程安全（内部解析器注册表只读），可复用单例；
     * 每个请求各自创建 Metadata / ParseContext / ContentHandler。
     */
    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * 提取并截断文本；失败/为空/过短抛 400/40000。
     *
     * @param file 已通过三重校验的上传文件
     * @return 截断至 {@link #MAX_CONTENT_LENGTH} 的提取文本（已 strip）
     */
    public String extract(MultipartFile file) {
        String text;
        try (InputStream in = file.getInputStream()) {
            // writeLimit = -1：不限制长度，上限截断由本方法统一负责
            // （默认构造只保留前 100 KB，会与 20000 字符上限的语义纠缠不清）
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            // 文件名参与类型探测；与"扩展名/MIME/内容特征"三重校验相互独立
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
            parser.parse(in, handler, metadata, pdfContext());
            text = handler.toString();
        }
        catch (IOException | SAXException | TikaException ex) {
            // 不记录文件内容，只记异常摘要（《系统架构设计》7.7 日志脱敏）
            log.warn("文本提取失败：{}", ex.getMessage());
            throw BusinessException.badRequest(EXTRACT_FAILED_MESSAGE);
        }

        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() < MIN_CONTENT_LENGTH) {
            log.warn("提取文本为空或过短（{} 字符），拒绝入库", trimmed.length());
            throw BusinessException.badRequest(EXTRACT_FAILED_MESSAGE);
        }
        return trimmed.length() > MAX_CONTENT_LENGTH
                ? trimmed.substring(0, MAX_CONTENT_LENGTH)
                : trimmed;
    }

    /**
     * PDF 解析配置：**关闭书签与批注文本提取**。
     *
     * <p>Tika 3.3.2 的 {@code PDFParserConfig.extractBookmarksText} 默认为 true，
     * 会把 PDF 大纲（书签）的标题当作正文追加到提取结果末尾。简历 PDF 常带
     * 「基本信息 / 实习经历 / 项目经历 …」大纲，于是入库文本尾部多出一串
     * TAB 分隔的章节名；定向优化的事实校验按块与章节核对原文证据，重复章节
     * 会造成大面积误判（2026-10-21 简历 203 实测：连续两次各约 26 项）。
     *
     * <p>批注同理：简历中的批注/评论不属于简历事实，默认也会被拼进正文。
     * 其余默认值（{@code enableAutoSpace} 等）保持不变，避免改变既有 DOCX/TXT
     * 的输出口径。AutoDetectParser 对非 PDF 文件会忽略该配置。
     */
    private static ParseContext pdfContext() {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractBookmarksText(false);
        config.setExtractAnnotationText(false);
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, config);
        return context;
    }

}
