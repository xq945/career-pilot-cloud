package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesBlockTypesAndSectionPath() {
        SectionedDocument document = parser.parse("""
                ## 项目经历
                ### 项目 A
                - 开发 3 个模块
                项目：智能求职辅助平台
                负责接口开发
                姓名：张三
                """);

        assertEquals(CoverageBlock.Type.HEADING, document.blocks.get(0).type);
        assertEquals(CoverageBlock.Type.HEADING, document.blocks.get(1).type);
        assertEquals(CoverageBlock.Type.LIST_ITEM, document.blocks.get(2).type);
        assertEquals(CoverageBlock.Type.PROJECT_LABEL, document.blocks.get(3).type);
        assertEquals(CoverageBlock.Type.PARAGRAPH, document.blocks.get(4).type);
        assertEquals(CoverageBlock.Type.CONTACT, document.blocks.get(5).type);
        assertEquals("项目经历", document.blocks.get(1).parentKey);
        assertEquals("项目经历/项目a", document.blocks.get(2).sectionKey);
        assertEquals("项目智能求职辅助平台", document.blocks.get(3).projectKey);
        assertTrue(document.complete);
    }

    @Test
    void normalTableBindsHeaderLabelsAndIsParsed() {
        SectionedDocument document = parser.parse("""
                ### 项目 A
                | 模块数 | 响应时间 |
                | --- | --- |
                | 3 | 54 毫秒 |
                """);

        CoverageBlock row = document.blocks.get(document.blocks.size() - 1);
        assertEquals(CoverageBlock.Type.TABLE_ROW, row.type);
        assertEquals(CoverageBlock.ParseStatus.PARSED, row.parseStatus);
        assertEquals("3", row.columns.get("模块数"));
        assertEquals("54 毫秒", row.columns.get("响应时间"));
        assertTrue(row.factTokens.stream().anyMatch(
                t -> t.kind() == FactToken.Kind.NUMBER && t.value().equals("54")));
    }

    @Test
    void tableWithMismatchedColumnCountIsPartial() {
        SectionedDocument document = parser.parse("""
                | 模块数 | 响应时间 |
                | --- | --- |
                | 3 | 54 毫秒 | 额外 |
                """);

        CoverageBlock row = document.blocks.get(document.blocks.size() - 1);
        assertEquals(CoverageBlock.ParseStatus.PARTIAL, row.parseStatus);
    }

    @Test
    void tableWithoutSeparatorIsPlainText() {
        SectionedDocument document = parser.parse("学校 | 专业 | 学历\n江西师范大学 | 数据科学 | 本科");

        CoverageBlock line = document.blocks.get(document.blocks.size() - 1);
        assertEquals(CoverageBlock.Type.PARAGRAPH, line.type);
        assertEquals(CoverageBlock.ParseStatus.PARSED, line.parseStatus);
    }

    @Test
    void bareTextSectionHeadingsAreRecognized() {
        SectionedDocument document = parser.parse("""
                基本信息
                · 姓名：张三
                实习经历
                · 江西云牛科技（后端开发） 2026.09-至今
                """);

        assertEquals(CoverageBlock.Type.HEADING, document.blocks.get(0).type);
        assertEquals("基本信息", document.blocks.get(0).sectionKey);
        assertEquals("基本信息", document.blocks.get(1).sectionKey);
        assertEquals(CoverageBlock.Type.HEADING, document.blocks.get(2).type);
        assertEquals("工作经历", document.blocks.get(2).sectionKey);
        assertEquals("工作经历", document.blocks.get(3).sectionKey);
    }

    @Test
    void fieldLabelWithColonIsNotASection() {
        SectionedDocument document = parser.parse("项目经历\n核心工作：\n1. 参与接口开发");

        assertEquals(CoverageBlock.Type.GENERIC, document.blocks.get(1).type);
        assertEquals("项目经历", document.blocks.get(2).sectionKey);
    }

    @Test
    void bulletWithPipesIsNotATable() {
        SectionedDocument document = parser.parse(
                "- 江西师范大学 | 数据科学与大数据技术 | 本科（2027届）");

        assertEquals(CoverageBlock.Type.LIST_ITEM, document.blocks.get(0).type);
        assertEquals(CoverageBlock.ParseStatus.PARSED, document.blocks.get(0).parseStatus);
    }

    @Test
    void headingLevelDoesNotChangeSectionKey() {
        SectionedDocument level2 = parser.parse("## 项目 A\n开发 3 个模块");
        SectionedDocument level3 = parser.parse("### 项目 A\n开发 3 个模块");

        assertEquals(level2.blocks.get(1).sectionKey, level3.blocks.get(1).sectionKey);
    }

    @Test
    void oversizedDocumentIsMarkedIncomplete() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < DocumentParser.MAX_BLOCKS + 5; i++) {
            text.append("第").append(i).append("行内容\n");
        }

        SectionedDocument document = parser.parse(text.toString());

        assertFalse(document.complete);
        assertTrue(document.blocks.size() >= DocumentParser.MAX_BLOCKS);
    }

    @Test
    void contactAndGenericBlocksDoNotCarryFactTokens() {
        SectionedDocument document = parser.parse("姓名：张三\n---\n2025年3月");

        assertTrue(document.blocks.get(0).nonFact);
        assertTrue(document.blocks.get(0).factTokens.isEmpty());
        assertTrue(document.blocks.get(1).nonFact);
        assertTrue(document.blocks.get(2).factTokens.stream()
                .anyMatch(t -> t.kind() == FactToken.Kind.DATE));
    }
}
