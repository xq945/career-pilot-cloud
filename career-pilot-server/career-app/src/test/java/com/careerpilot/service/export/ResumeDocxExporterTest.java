package com.careerpilot.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

/**
 * DOCX 导出器的单测（《测试与验收方案》1.1 的"DOCX 导出"行）。
 *
 * 断言口径：生成字节可被 {@link XWPFDocument} 读回（即真实可打开的 docx），
 * 标题/列表/加粗结构正确，空输入不崩；页面为 A4。
 */
class ResumeDocxExporterTest {

    private final ResumeDocxExporter exporter = new ResumeDocxExporter();

    @Test
    void toDocx_producesReadableDocumentWithExpectedStructure() throws IOException {
        String markdown = """
                # 张三
                ## 技能
                - Java（项目主力语言）
                - **Spring Boot**
                1. 第一项
                普通段落""";

        byte[] bytes = exporter.toDocx(markdown);

        assertThat(bytes).isNotEmpty();
        // docx 是 ZIP 容器：前两字节为 PK
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            assertThat(paragraphs).hasSize(6);
            assertThat(paragraphs.get(0).getText()).isEqualTo("张三");
            assertThat(paragraphs.get(0).getRuns().get(0).isBold()).isTrue();
            assertThat(paragraphs.get(1).getText()).isEqualTo("技能");
            assertThat(paragraphs.get(2).getText()).isEqualTo("• Java（项目主力语言）");
            assertThat(paragraphs.get(3).getText()).isEqualTo("• Spring Boot");
            assertThat(paragraphs.get(3).getRuns())
                    .anyMatch(run -> run.getText(0).equals("Spring Boot") && run.isBold());
            assertThat(paragraphs.get(4).getText()).isEqualTo("1. 第一项");
            assertThat(paragraphs.get(5).getText()).isEqualTo("普通段落");

            // 页面取 A4（POI 新建文档默认 Letter）；getW() 是 XMLBeans union 类型，返回 Object
            Object width = document.getDocument().getBody().getSectPr().getPgSz().getW();
            assertThat(String.valueOf(width)).isEqualTo("11906");
        }
    }

    @Test
    void toDocx_blankInput_returnsValidEmptyDocument() throws IOException {
        byte[] bytes = exporter.toDocx("   \n\n  ");

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(document.getParagraphs()).isEmpty();
        }
    }

    @Test
    void toDocx_nullInput_doesNotThrow() {
        assertThat(exporter.toDocx(null)).isNotEmpty();
    }

    @Test
    void toDocx_veryLongLine_doesNotThrow() {
        byte[] bytes = exporter.toDocx("正文".repeat(10000));

        assertThat(bytes).isNotEmpty();
    }

}
