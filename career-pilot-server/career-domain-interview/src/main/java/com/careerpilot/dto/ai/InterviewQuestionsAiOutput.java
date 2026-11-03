package com.careerpilot.dto.ai;

import java.util.List;

/**
 * 出题场景的模型输出结构（《Agent设计》§4.5）。
 *
 * 根对象而非裸数组：与 {@link JobAnalysisAiOutput} 等既有输出结构一致，JSON Schema 更稳定。
 * 该类型同时用于生成 Schema（由 BeanOutputConverter 注入提示词），字段名即模型必须遵守的键名。
 */
public class InterviewQuestionsAiOutput {

    private List<GeneratedQuestion> questions;

    public List<GeneratedQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<GeneratedQuestion> questions) {
        this.questions = questions;
    }

}
