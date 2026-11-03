package com.careerpilot.dto;

/**
 * 获取岗位分析（《API接口设计》5.7）的响应 data。
 *
 * 与简历分析（4.6）的同一约定：**无分析结果时 HTTP 200 + analyzed=false**（不是 404）；
 * 多出的 `stale` 标识"分析基于旧版岗位描述"（简历文本不可变，故简历接口没有该字段）。
 *
 * 两种"没有分析"与"分析已过期"都由本结构一次表达：
 * - 未分析：analyzed=false、stale=false、analysis=null；
 * - 已分析：analyzed=true、stale 按 source_hash 比对结果、analysis 为结果体。
 */
public class JobAnalysisView {

    private boolean analyzed;

    /** 已有分析但描述已被修改（分析基于旧版描述）；analyzed=false 时恒为 false */
    private boolean stale;

    private JobAnalysisDetail analysis;

    /** 尚无分析结果（analyzed=false、stale=false、analysis=null） */
    public static JobAnalysisView notAnalyzed() {
        JobAnalysisView view = new JobAnalysisView();
        view.analyzed = false;
        view.stale = false;
        view.analysis = null;
        return view;
    }

    /**
     * 已有分析结果。
     *
     * @param stale 当前描述与最新成功分析的 source_hash 是否不一致
     *              （由 {@code JobAnalysisService.isStale} 判定，与列表/详情同源）
     */
    public static JobAnalysisView of(JobAnalysisDetail analysis, boolean stale) {
        JobAnalysisView view = new JobAnalysisView();
        view.analyzed = true;
        view.stale = stale;
        view.analysis = analysis;
        return view;
    }

    public boolean isAnalyzed() {
        return analyzed;
    }

    public boolean isStale() {
        return stale;
    }

    public JobAnalysisDetail getAnalysis() {
        return analysis;
    }

}
