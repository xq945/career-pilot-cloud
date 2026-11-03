package com.careerpilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.SkillItem;

/**
 * 结构化输出清洗规则的单测（《测试与验收方案》1.1：结构化输出校验）。
 *
 * 逐条对应《Agent设计》6.4 的简历分析行：超限截断、缺必需字段的元素丢弃、
 * 约定外字段丢弃；纯函数测试，不依赖 Spring 上下文与数据库。
 */
class StructuredOutputSanitizerTest {

    private static final String SCENE = "test";

    // ---------------------------------------------------------------- limitText

    @Test
    void limitText_overLength_truncatesToLimit() {
        String text = "字".repeat(2500);
        String result = StructuredOutputSanitizer.limitText(text, 2000, "summary", SCENE);
        assertThat(result).hasSize(2000);
    }

    @Test
    void limitText_withinLength_keepsOriginal() {
        String text = "短文本";
        assertThat(StructuredOutputSanitizer.limitText(text, 2000, "summary", SCENE)).isSameAs(text);
    }

    @Test
    void limitText_null_isAllowed() {
        assertThat(StructuredOutputSanitizer.limitText(null, 2000, "summary", SCENE)).isNull();
    }

    // ---------------------------------------------------------------- limitList

    @Test
    void limitList_overLimit_truncatesToLimit() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            list.add("项" + i);
        }
        List<String> result = StructuredOutputSanitizer.limitList(list, 50, "skills", SCENE);
        assertThat(result).hasSize(50);
        assertThat(result.get(0)).isEqualTo("项0");
    }

    @Test
    void limitList_withinLimit_keepsOriginal() {
        List<String> list = List.of("a", "b");
        assertThat(StructuredOutputSanitizer.limitList(list, 50, "skills", SCENE)).isSameAs(list);
    }

    // ---------------------------------------------------------------- cleanElements

    @Test
    void cleanElements_unknownFields_areDroppedAndElementKept() {
        SkillItem item = skill("Java");
        item.putUnknownField("foo", 1);
        item.putUnknownField("bar", "x");

        List<SkillItem> result = StructuredOutputSanitizer.cleanElements(
                List.of(item), SkillItem::getName, "name", "skills", SCENE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Java");
        // 约定外字段被取出（清空），不会再出现在落库/响应中
        assertThat(result.get(0).drainUnknownFields()).isEmpty();
    }

    @Test
    void cleanElements_missingRequiredKey_dropsElement() {
        SkillItem valid = skill("Java");
        SkillItem missingName = skill(null);
        SkillItem blankName = skill("   ");

        List<SkillItem> result = StructuredOutputSanitizer.cleanElements(
                List.of(valid, missingName, blankName), SkillItem::getName, "name", "skills", SCENE);

        assertThat(result).containsExactly(valid);
    }

    @Test
    void cleanElements_nullRequiredKey_onlyDrainsUnknownFields() {
        SkillItem item = skill("Java");
        item.putUnknownField("foo", 1);

        List<SkillItem> result = StructuredOutputSanitizer.cleanElements(
                List.of(item), null, null, "education", SCENE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).drainUnknownFields()).isEmpty();
    }

    @Test
    void cleanElements_nullList_isAllowed() {
        assertThat(StructuredOutputSanitizer.cleanElements(null, SkillItem::getName, "name", "skills", SCENE))
                .isNull();
    }

    private static SkillItem skill(String name) {
        SkillItem item = new SkillItem();
        item.setName(name);
        return item;
    }

}
