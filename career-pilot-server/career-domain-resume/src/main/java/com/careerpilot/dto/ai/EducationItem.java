package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 教育经历（《Agent设计》6.1、API 4.5）：四个字段均可空（未识别到时为 null）。
 */
public class EducationItem extends AiOutputElement {

    private String school;

    private String major;

    private String degree;

    private String period;

    public String getSchool() {
        return school;
    }

    public void setSchool(String school) {
        this.school = school;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getDegree() {
        return degree;
    }

    public void setDegree(String degree) {
        this.degree = degree;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

}
