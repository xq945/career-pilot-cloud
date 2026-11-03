package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 文本提取（2026-10-21）：Tika 默认会把 PDF 书签与批注拼进正文，
 * 导致简历内容尾部出现一串章节名、事实校验大面积误判。这里用 PDFBox
 * 生成带大纲的真实 PDF，固定「书签不进入提取文本」的行为。
 */
class TextExtractionServiceTest {

    private static final String BODY =
            "RESUME BODY: Java Spring Boot MyBatis MySQL Redis. "
                    + "Participated in backend development and interface design.";

    private static final String BOOKMARK_TITLE = "BASICINFO SECTION";

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void pdf_doesNotExtractBookmarkTitles() throws Exception {
        byte[] pdf = pdfWithOutline();

        String text = service.extract(
                new MockMultipartFile("file", "resume.pdf", "application/pdf", pdf));

        assertTrue(text.contains("RESUME BODY"), "正文必须被提取");
        assertFalse(text.contains(BOOKMARK_TITLE),
                "PDF 大纲标题不得进入提取文本（Tika extractBookmarksText 默认 true）");
    }

    @Test
    void txt_isExtractedAsIs() {
        String content = "这是一份纯文本简历，包含足够长度以便通过最小长度校验，"
                + "覆盖实习经历、项目经历、技能清单与荣誉证书等内容，用于验证提取逻辑不做任何改写。";

        String text = service.extract(
                new MockMultipartFile("file", "resume.txt", "text/plain",
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(content, text);
    }

    private static byte[] pdfWithOutline() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(BODY);
                stream.endText();
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(BOOKMARK_TITLE);
            outline.addLast(item);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

}
