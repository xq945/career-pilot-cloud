package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交回答的请求体（《API接口设计》§7.2）。
 *
 * `@NotBlank` 保证纯空白串不算有效作答；文本**原样入库、不做 trim**
 * （用户回答是评价模型的输入，加工会改变模型所见）。
 */
public class AnswerSubmitRequest {

    @NotBlank(message = "回答不能为空")
    @Size(max = 5000, message = "回答不能超过 5000 个字符")
    private String answer;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

}
