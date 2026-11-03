package com.careerpilot.service.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Component;

/**
 * 优化稿的 DOCX 导出器（《API接口设计》§6A.6、《系统架构设计》§6.4）：Markdown → Word。
 *
 * <p>支持的 Markdown 子集（与提示词要求模型输出的结构一致）：
 * {@code #} / {@code ##} / {@code ###} 标题、{@code -} / {@code *} 无序列表、
 * {@code 1.} 有序列表、{@code **加粗**} 行内强调、普通段落。
 * 刻意**不引入完整 Markdown 解析库**——需要渲染的语法由提示词约束在可控子集内。
 *
 * <p>中文字体必须显式设置 eastAsia（只设 ascii 时中文会回退默认字体，Word 打开观感异常）；
 * 页面取 A4；字号用 half-point（21 = 10.5pt，五号）。
 *
 * <p>纯转换、无副作用：调用方拿到字节流直接下发，**不落盘**（与简历原文下载的模式一致）。
 */
@Component
public class ResumeDocxExporter {

    /** 正文字体（同时设置 ascii / eastAsia / hAnsi，保证中英文一致） */
    private static final String FONT = "微软雅黑";

    /** 字号（half-points）：正文 10.5pt / H3 12pt / H2 15pt / H1 18pt */
    private static final int BODY_SIZE = 21;

    private static final int H3_SIZE = 24;

    private static final int H2_SIZE = 30;

    private static final int H1_SIZE = 36;

    /** 行内加粗：**文本** */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    /** 有序列表：`1. ` / `1) `（数字保留为文本前缀，不依赖 numbering.xml） */
    private static final Pattern ORDERED = Pattern.compile("^\\d+[.)]\\s+.*");

    /** A4 尺寸（twips：210mm × 297mm） */
    private static final BigInteger A4_WIDTH = BigInteger.valueOf(11906);

    private static final BigInteger A4_HEIGHT = BigInteger.valueOf(16838);

    /** 列表缩进（twips：约 0.37cm） */
    private static final int LIST_INDENT = 210;

    /**
     * 把 Markdown 优化稿转换为 DOCX 字节流。
     *
     * @param markdown 当前生效的优化稿（用户编辑稿优先）
     * @return .docx 文件字节
     */
    public byte[] toDocx(String markdown) {
        String text = markdown == null ? "" : markdown;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            applyA4(document);
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("### ")) {
                    addParagraph(document, line.substring(4), H3_SIZE, true, ParagraphAlignment.LEFT, 0);
                }
                else if (line.startsWith("## ")) {
                    addParagraph(document, line.substring(3), H2_SIZE, true, ParagraphAlignment.LEFT, 0);
                }
                else if (line.startsWith("# ")) {
                    addParagraph(document, line.substring(2), H1_SIZE, true, ParagraphAlignment.LEFT, 0);
                }
                else if (line.startsWith("- ") || line.startsWith("* ")) {
                    addParagraph(document, "• " + line.substring(2), BODY_SIZE, false,
                            ParagraphAlignment.LEFT, LIST_INDENT);
                }
                else if (ORDERED.matcher(line).matches()) {
                    addParagraph(document, line, BODY_SIZE, false, ParagraphAlignment.LEFT, LIST_INDENT);
                }
                else {
                    addParagraph(document, line, BODY_SIZE, false, ParagraphAlignment.LEFT, 0);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            // 内存生成失败属意外故障：不记录文稿内容（隐私），只留异常摘要
            throw new IllegalStateException("DOCX 生成失败：" + ex.getMessage(), ex);
        }
    }

    /** 段落（含 **加粗** 行内拆分）；indentTwips > 0 时按列表缩进 */
    private static void addParagraph(XWPFDocument document, String text, int size, boolean bold,
                                     ParagraphAlignment alignment, int indentTwips) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        if (indentTwips > 0) {
            paragraph.setIndentationLeft(indentTwips);
        }
        // 行距：标题紧凑、正文 1.4 倍（rule 缺省 AUTO）
        paragraph.setSpacingBetween(bold ? 1.2 : 1.4);

        for (Segment segment : splitBold(text)) {
            XWPFRun run = paragraph.createRun();
            run.setText(segment.text());
            applyFont(run, size, bold || segment.bold());
        }
    }

    /** 按 ** 标记拆分行内文本（无标记时返回单段） */
    private static List<Segment> splitBold(String text) {
        List<Segment> segments = new ArrayList<>();
        var matcher = BOLD.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                segments.add(new Segment(text.substring(last, matcher.start()), false));
            }
            segments.add(new Segment(matcher.group(1), true));
            last = matcher.end();
        }
        if (last < text.length()) {
            segments.add(new Segment(text.substring(last), false));
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(text, false));
        }
        return segments;
    }

    /**
     * 字体 + 字号 + 加粗。
     *
     * 中文字体必须显式设置 eastAsia：{@code setFontFamily(String)} 只写 ascii/hAnsi，
     * 中文会回退默认字体、Word 打开观感异常；带 {@code FontCharRange} 的重载由 POI 高层 API
     * 完成 rFonts 的 eastAsia 设置，避免直接操作 CT 对象。
     */
    private static void applyFont(XWPFRun run, int size, boolean bold) {
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontFamily(FONT);
        run.setFontFamily(FONT, XWPFRun.FontCharRange.eastAsia);
    }

    /** 页面取 A4（POI 新建文档默认为 Letter） */
    private static void applyA4(XWPFDocument document) {
        CTSectPr sectPr = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pageSize.setW(A4_WIDTH);
        pageSize.setH(A4_HEIGHT);
    }

    /** 行内片段：普通文本 / 加粗文本 */
    private record Segment(String text, boolean bold) {
    }

}
