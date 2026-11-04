package com.careerpilot.dto;

/**
 * 匹配服务的内部创建请求（阶段 7）。
 *
 * <p>不传分析 ID（null）时表示"缺分析则自动补齐"（Agent 工具入口）；
 * 传入双方分析 ID 时按指定版本创建（已由调用方完成分析的上游使用）。
 */
public class MatchInternalCreateRequest {

    private Long resumeId;

    private Long jobId;

    private Long resumeAnalysisId;

    private Long jobAnalysisId;

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getResumeAnalysisId() {
        return resumeAnalysisId;
    }

    public void setResumeAnalysisId(Long resumeAnalysisId) {
        this.resumeAnalysisId = resumeAnalysisId;
    }

    public Long getJobAnalysisId() {
        return jobAnalysisId;
    }

    public void setJobAnalysisId(Long jobAnalysisId) {
        this.jobAnalysisId = jobAnalysisId;
    }
}
