package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 匹配明细项（《数据库设计》5.6、《API接口设计》6.1）：{@code {text, dimension}}。
 *
 * <p>同时是**模型输出元素、match_record 三个 JSON 列的元素与 API 响应元素**——
 * 三处一套类型、零转换（与 SkillItem 的处理方式一致）。
 *
 * <p>继承 {@link AiOutputElement}：元素内出现约定外字段时收集并记日志后丢弃。
 */
public class MatchItem extends AiOutputElement {

    /** 说明文案（含简短证据；差距项必须使用「简历未体现」等受控措辞，《数据库设计》6.5） */
    private String text;

    /** 该明细影响的维度 key（五个白名单之一，取值同 {@code MatchDimension#getKey()}；未给出归因时为 null） */
    private String dimension;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

}
