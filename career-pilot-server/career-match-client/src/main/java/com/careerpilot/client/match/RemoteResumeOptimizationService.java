package com.careerpilot.client.match;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.OptimizedResumeFile;
import com.careerpilot.dto.ResumeOptimizationContentRequest;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.service.ResumeOptimizationService;

/**
 * ResumeOptimizationService 的远程实现（app.match.mode=remote）。
 *
 * <p>公开 JSON 接口走 /api/optimizations；跨域查询走 /internal/optimizations。
 * DOCX 导出是二进制端点，只由 career-match 的控制器受理，单体侧不再有调用方。
 */
@Service
@ConditionalOnProperty(prefix = "app.match", name = "mode", havingValue = "remote")
public class RemoteResumeOptimizationService implements ResumeOptimizationService {

    private final OptimizationPublicClient publicClient;

    private final OptimizationInternalClient internalClient;

    public RemoteResumeOptimizationService(OptimizationPublicClient publicClient,
                                           OptimizationInternalClient internalClient) {
        this.publicClient = publicClient;
        this.internalClient = internalClient;
    }

    @Override
    public ResumeOptimizationDetail create(Long userId, ResumeOptimizationCreateRequest request) {
        return publicClient.create(request).getData();
    }

    @Override
    public PageResult<ResumeOptimizationView> list(Long userId, PageQuery query) {
        return publicClient.list(query.getPage(), query.getSize()).getData();
    }

    @Override
    public ResumeOptimizationDetail detail(Long userId, Long id) {
        return publicClient.detail(id).getData();
    }

    @Override
    public ResumeOptimizationDetail saveContent(Long userId, Long id, String content) {
        ResumeOptimizationContentRequest request = new ResumeOptimizationContentRequest();
        request.setContent(content);
        return publicClient.saveContent(id, request).getData();
    }

    @Override
    public ResumeOptimizationDetail evaluate(Long userId, Long id) {
        return publicClient.evaluate(id).getData();
    }

    @Override
    public ResumeOptimizationDetail retry(Long userId, Long id) {
        return publicClient.retry(id).getData();
    }

    @Override
    public OptimizedResumeFile readExport(Long userId, Long id) {
        throw new UnsupportedOperationException(
                "优化稿 DOCX 导出由 career-match 的 /api/optimizations/{id}/export 直接受理，单体不应调用");
    }

    @Override
    public ResumeOptimization getOwned(Long userId, Long id) {
        return internalClient.getOwned(id);
    }

    @Override
    public ResumeOptimization getOptimizationById(Long id) {
        return internalClient.getById(id);
    }

    @Override
    public List<ResumeOptimization> listOptimizationsByIds(List<Long> ids) {
        return internalClient.listByIds(ids);
    }

    @Override
    public List<ResumeOptimization> recentSuccessOptimizations(Long userId, int limit) {
        return internalClient.recent(limit);
    }
}
