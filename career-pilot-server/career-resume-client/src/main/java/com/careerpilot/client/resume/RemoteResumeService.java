package com.careerpilot.client.resume;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeDetail;
import com.careerpilot.dto.ResumeFile;
import com.careerpilot.dto.ResumeUploadRequest;
import com.careerpilot.dto.ResumeUploadResponse;
import com.careerpilot.dto.ResumeView;
import com.careerpilot.entity.Resume;
import com.careerpilot.service.ResumeService;

/**
 * ResumeService 的远程实现（app.resume.mode=remote）。
 *
 * <p>列表/详情/删除走 /api/resumes（内部令牌已由 Feign 拦截器注入）；跨域查询走
 * /internal/resumes。上传与原始文件下载是 multipart/二进制端点，只由 career-resume
 * 的 ResumeController 受理——单体侧不再有调用方，命中即为装配错误，故直接失败。
 */
@Service
@ConditionalOnProperty(prefix = "app.resume", name = "mode", havingValue = "remote")
public class RemoteResumeService implements ResumeService {

    private final ResumePublicClient publicClient;

    private final ResumeInternalClient internalClient;

    public RemoteResumeService(ResumePublicClient publicClient, ResumeInternalClient internalClient) {
        this.publicClient = publicClient;
        this.internalClient = internalClient;
    }

    @Override
    public ResumeUploadResponse upload(Long userId, ResumeUploadRequest request, MultipartFile file) {
        throw new UnsupportedOperationException(
                "简历上传由 career-resume 的 /api/resumes 直接受理，单体不应调用");
    }

    @Override
    public PageResult<ResumeView> list(Long userId, PageQuery query) {
        return publicClient.list(query.getPage(), query.getSize()).getData();
    }

    @Override
    public ResumeDetail detail(Long userId, Long id) {
        return publicClient.detail(id).getData();
    }

    @Override
    public void delete(Long userId, Long id) {
        publicClient.delete(id);
    }

    @Override
    public ResumeFile readFile(Long userId, Long id) {
        throw new UnsupportedOperationException(
                "简历原文由 career-resume 的 /api/resumes/{id}/file 直接受理，单体不应调用");
    }

    @Override
    public Resume getOwnedResume(Long userId, Long id) {
        return internalClient.getOwned(id);
    }

    @Override
    public Resume getResumeById(Long id) {
        return internalClient.getById(id);
    }

    @Override
    public List<Resume> listOwnedResumes(Long userId) {
        return internalClient.listOwned();
    }

    @Override
    public List<Resume> listResumesByIds(List<Long> ids) {
        return internalClient.listByIds(ids);
    }

    @Override
    public long countOwnedResumes(Long userId) {
        return internalClient.countOwned();
    }
}
