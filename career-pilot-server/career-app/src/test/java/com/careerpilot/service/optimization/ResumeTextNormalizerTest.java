package com.careerpilot.service.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 简历原文归一化（2026-10-21）：PDF 书签残留与硬折行是事实校验的主要误报来源，
 * 这里固定两类清洗的边界，避免后续调整时误伤正文。
 */
class ResumeTextNormalizerTest {

    @Test
    void nullInput_returnsNull() {
        assertNull(ResumeTextNormalizer.normalize(null));
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertEquals("", ResumeTextNormalizer.normalize(""));
    }

    @Test
    void stripsTrailingBookmarkSectionNames() {
        String text = """
                荣誉证书
                · 中级软件设计师

                \t基本信息
                \t实习经历
                \t项目经历
                \t专业技能
                \t荣誉证书""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertFalse(normalized.contains("\t基本信息"), "书签残留应被删除");
        assertFalse(normalized.contains("\t实习经历"), "书签残留应被删除");
        assertTrue(normalized.contains("中级软件设计师"), "正文事实必须保留");
    }

    @Test
    void keepsSingleSectionNameAtTail() {
        String text = """
                荣誉证书
                · 中级软件设计师
                荣誉证书""";

        String normalized = ResumeTextNormalizer.normalize(text);

        // 连续少于 2 行不判定为书签，避免误删只有空章节的正常简历
        assertTrue(normalized.contains("荣誉证书\n") || normalized.endsWith("荣誉证书"));
    }

    @Test
    void doesNotStripSectionNamesInsideBody() {
        String text = """
                基本信息
                姓名：张广

                实习经历
                江西云牛科技 2026.09-至今

                项目经历
                智能求职辅助平台 2026.07-2026.10""";

        assertEquals(text, ResumeTextNormalizer.normalize(text));
    }

    @Test
    void mergesChineseHardWrap() {
        String text = """
                项目简介：基于前后端分离架构开发的 AI 求职辅助平台，覆盖简历与岗位管理、简历定
                向优化、人岗匹配、AI 模拟面试""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("简历定向优化"), "PDF 硬折行应被合并");
        assertFalse(normalized.contains("简历定\n向优化"));
    }

    @Test
    void mergesAtNumberBoundary() {
        String text = """
                成果：接口
                54 毫秒返回""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("成果：接口 54 毫秒返回"));
    }

    @Test
    void mergesEnglishWordsWithSpace() {
        String text = """
                技术栈：Java 21、Spring
                Boot、MyBatis""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("Spring Boot"), "英文折行应补空格后合并");
    }

    @Test
    void doesNotMergeListItems() {
        String text = """
                1. 参与公司官网日常维护
                2. 参与业务系统接口开发""";

        assertEquals(text, ResumeTextNormalizer.normalize(text));
    }

    @Test
    void mergesListContinuationLine() {
        String text = """
                4. 负责物业公告模块接口开发，交付元数据获取、条件分页查询、聚合统计、导出及增
                删等接口，覆盖公告查询到导出的完整链路。""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("导出及增删等接口"), "列表项的 PDF 续行必须合并");
        assertFalse(normalized.contains("导出及增\n删等接口"));
    }

    @Test
    void mergesContinuationEndingWithConnector() {
        String text = """
                1. 双令牌认证：基于 Spring Security +
                JWT 实现 Access（2h）/ Refresh（7d）双令牌轮换
                2. 高频写：改由 Redis 承接（String 自增 /
                Set 去重）""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("Spring Security + JWT"), "行末为连接符时仍应续行合并");
        assertTrue(normalized.contains("String 自增 / Set 去重"));
    }

    @Test
    void mergesContinuationAfterChinesePunctuation() {
        String text = """
                1. 简历定向优化：改造为 Redis 异步队列，提交即返回、
                后台消费，并以未确认消息回收""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("提交即返回、后台消费"),
                "折行落在顿号后时仍应合并（顿号允许出现在中文行尾）");
    }

    @Test
    void doesNotMergeAcrossBlankLine() {
        String text = """
                项目简介：第一段话
                继续第一段

                实习经历""";

        String normalized = ResumeTextNormalizer.normalize(text);

        assertTrue(normalized.contains("项目简介：第一段话继续第一段"));
        assertTrue(normalized.contains("\n\n实习经历"), "空行分段必须保留");
    }

    @Test
    void doesNotMergeParagraphEndingWithSentenceMark() {
        String text = """
                负责接口开发。
                下一段开头""";

        assertEquals(text, ResumeTextNormalizer.normalize(text));
    }

    @Test
    void doesNotMergeTableRows() {
        String text = """
                | 项目 | 时间 |
                | --- | --- |""";

        assertEquals(text, ResumeTextNormalizer.normalize(text));
    }

    @Test
    void doesNotMergeContactFields() {
        String text = """
                姓名：张广
                电话：19880735898""";

        assertEquals(text, ResumeTextNormalizer.normalize(text));
    }

    @Test
    void isIdempotent() {
        String text = """
                项目简介：覆盖简历与岗位管理、简历定
                向优化、人岗匹配

                \t基本信息
                \t实习经历""";

        String once = ResumeTextNormalizer.normalize(text);
        String twice = ResumeTextNormalizer.normalize(once);

        assertEquals(once, twice);
    }

    @Test
    void docxStyleTextIsUnchanged() {
        String text = """
                基本信息
                · \t姓名：张广\t\t年龄：21
                · \t学校：江西师范大学\t\t专业：数据科学与大数据技术
                实习经历
                \t江西云牛科技（后端开发工程师）\t\t2026.09-至今
                1. \t参与公司 AI Agent Skills 和知识库的维护，整理开发规范及组件文档
                2. \t参与公司官方网站的日常维护，根据需求完成页面内容更新、布局调整及样式优化""";

        assertEquals(text, ResumeTextNormalizer.normalize(text),
                "DOCX 文本无书签、无硬折行，归一化不应改变它");
    }

}
