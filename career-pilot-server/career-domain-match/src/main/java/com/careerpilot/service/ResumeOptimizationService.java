package com.careerpilot.service;

import java.util.List;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.OptimizedResumeFile;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;
import com.careerpilot.entity.ResumeOptimization;

/**
 * 简历定向优化域的服务契约（阶段 7）：本地实现留在 career-match 服务，单体侧由远程实现调用。
 */
public interface ResumeOptimizationService {

    // ---------------------------------------------------------------- 公开 API（6A.1–6A.7）

    ResumeOptimizationDetail create(Long userId, ResumeOptimizationCreateRequest request);

    PageResult<ResumeOptimizationView> list(Long userId, PageQuery query);

    ResumeOptimizationDetail detail(Long userId, Long id);

    ResumeOptimizationDetail saveContent(Long userId, Long id, String content);

    ResumeOptimizationDetail evaluate(Long userId, Long id);

    ResumeOptimizationDetail retry(Long userId, Long id);

    OptimizedResumeFile readExport(Long userId, Long id);

    // ---------------------------------------------------------------- 跨域查询（阶段 7 新增）

    /** 归属校验：不存在或属于他人一律 404（面试来源校验用） */
    ResumeOptimization getOwned(Long userId, Long id);

    /** 按 ID 取优化记录（不过滤 status，供历史记录/来源展示）；不存在返回 null */
    ResumeOptimization getOptimizationById(Long id);

    /** 批量取优化记录（不过滤 status，供面试列表装配版本号） */
    List<ResumeOptimization> listOptimizationsByIds(List<Long> ids);

    /** 本人最近 N 条**成功完成**的优化（仪表盘最近任务） */
    List<ResumeOptimization> recentSuccessOptimizations(Long userId, int limit);

    /**
     * 当前生效内容：用户编辑稿优先，无编辑稿时回落模型原稿（展示、评估、导出同口径）。
     */
    static String currentContent(ResumeOptimization record) {
        String edited = record.getEditedContent();
        return edited == null || edited.isBlank() ? record.getOptimizedContent() : edited;
    }
}
