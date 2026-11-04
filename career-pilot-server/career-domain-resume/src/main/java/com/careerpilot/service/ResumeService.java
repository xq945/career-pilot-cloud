package com.careerpilot.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeDetail;
import com.careerpilot.dto.ResumeFile;
import com.careerpilot.dto.ResumeUploadRequest;
import com.careerpilot.dto.ResumeUploadResponse;
import com.careerpilot.dto.ResumeView;
import com.careerpilot.entity.Resume;

/**
 * 简历域的服务契约（阶段 5）：本地实现留在简历服务，单体侧由远程实现调用。
 */
public interface ResumeService {

    ResumeUploadResponse upload(Long userId, ResumeUploadRequest request, MultipartFile file);

    PageResult<ResumeView> list(Long userId, PageQuery query);

    ResumeDetail detail(Long userId, Long id);

    void delete(Long userId, Long id);

    ResumeFile readFile(Long userId, Long id);

    /** 归属校验：不存在或属于他人一律 404 */
    Resume getOwnedResume(Long userId, Long id);

    // ---------------------------------------------------------------- 单体跨域查询（阶段 5 新增）

    /** 按 ID 取简历（不过滤 status，供历史记录/聚合展示）；不存在返回 null */
    Resume getResumeById(Long id);

    /** 本人有效简历列表（Agent 上下文、聚合用） */
    List<Resume> listOwnedResumes(Long userId);

    /** 批量取简历（不过滤 status，供列表装配） */
    List<Resume> listResumesByIds(List<Long> ids);

    /** 本人有效简历数（仪表盘） */
    long countOwnedResumes(Long userId);
}
