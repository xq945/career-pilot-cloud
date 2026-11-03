package com.careerpilot.dto;

/**
 * 获取简历分析（《API接口设计》4.6）的响应 data。
 *
 * **无分析结果时返回 HTTP 200 + analyzed=false**（不是 404）——
 * 避免前端把「未分析」误判为错误。两个静态工厂表达该约定。
 */
public class ResumeAnalysisView {

    private boolean analyzed;

    private ResumeAnalysisDetail analysis;

    /** 尚无分析结果（analyzed=false，analysis=null） */
    public static ResumeAnalysisView notAnalyzed() {
        ResumeAnalysisView view = new ResumeAnalysisView();
        view.analyzed = false;
        view.analysis = null;
        return view;
    }

    /** 已有分析结果 */
    public static ResumeAnalysisView of(ResumeAnalysisDetail analysis) {
        ResumeAnalysisView view = new ResumeAnalysisView();
        view.analyzed = true;
        view.analysis = analysis;
        return view;
    }

    public boolean isAnalyzed() {
        return analyzed;
    }

    public ResumeAnalysisDetail getAnalysis() {
        return analysis;
    }

}
