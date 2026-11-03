package com.careerpilot.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.Hashing;
import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.dto.ai.ResumeAnalysisAiOutput;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.service.ai.ResumeAiService;

/**
 * 简历分析业务编排（《API接口设计》4.5–4.6、《数据库设计》5.2–5.3）。
 *
 * - **每次调用都重新分析**（2026-10-13 用户拍板）：不做 source_hash 复用判断，
 *   一律调用模型并生成新版本（不覆盖旧结果）；source_hash 仍随记录落库（数据追溯）；
 * - **版本**：新增 version_no，并发由 uk_resume_analysis_version 唯一索引兜底 + 少量重试；
 * - **失败不落库**：模型调用与解析在 INSERT 之前完成，任何 502 都先于写入抛出
 *   （《数据库设计》5.3：第一版只保存成功结果）；
 * - 写路径不加 @Transactional（单条 INSERT 本身原子，与 M3 的写路径约定一致）。
 *
 * 本类的 {@link #analyze(Resume)} 是 REST 与 M8 的 Agent 工具（{@code @Tool}）**共用入口**，
 * 用户身份一律由调用方从认证上下文取得后传入，绝不由模型提供。
 */
@Service
public class ResumeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);

    /** 版本号并发冲突的最大尝试次数（《数据库设计》5.2：SELECT MAX+1 配合唯一索引重试） */
    private static final int MAX_INSERT_ATTEMPTS = 3;

    private final ResumeService resumeService;

    private final ResumeAiService resumeAiService;

    private final ResumeAnalysisMapper resumeAnalysisMapper;

    public ResumeAnalysisService(ResumeService resumeService, ResumeAiService resumeAiService,
                                 ResumeAnalysisMapper resumeAnalysisMapper) {
        this.resumeService = resumeService;
        this.resumeAiService = resumeAiService;
        this.resumeAnalysisMapper = resumeAnalysisMapper;
    }

    // ---------------------------------------------------------------- 对外入口

    /** 发起分析（4.5）：归属校验后进入核心流程 */
    public ResumeAnalysisDetail analyze(Long userId, Long resumeId) {
        return analyze(resumeService.getOwnedResume(userId, resumeId));
    }

    /**
     * 核心入口（M8 的 @Tool 与后续里程碑复用）：入参为**已归属校验**的简历实体。
     *
     * 流程：文本非空校验 → 计算 source_hash → 调用模型、清洗、落新版本。
     * **不做复用判断**：每次调用都重新分析（《API接口设计》4.5，2026-10-13 用户拍板）。
     */
    public ResumeAnalysisDetail analyze(Resume resume) {
        String content = resume.getContent();
        if (content == null || content.isBlank()) {
            throw BusinessException.badRequest("简历文本为空，无法分析");
        }
        // 《数据库设计》5.2：source_hash 只对入库后的文本计算，不得重新解析原文件
        String sourceHash = Hashing.sha256Hex(content);
        return generateAndSave(resume, sourceHash);
    }

    /** 获取最新一版成功分析（4.6）：无结果时返回 analyzed=false（HTTP 200，不是 404） */
    public ResumeAnalysisView getLatest(Long userId, Long resumeId) {
        Resume resume = resumeService.getOwnedResume(userId, resumeId);
        ResumeAnalysis latest = findLatestSuccess(resume.getId());
        return latest == null
                ? ResumeAnalysisView.notAnalyzed()
                : ResumeAnalysisView.of(toDetail(latest));
    }

    // ---------------------------------------------------------------- 生成与落库

    /** 调用模型 → 清洗 → 落库。模型调用只发生一次，版本冲突重试期间不重复计费。 */
    private ResumeAnalysisDetail generateAndSave(Resume resume, String sourceHash) {
        AiCallResult<ResumeAnalysisAiOutput> result = resumeAiService.analyze(resume.getContent());

        ResumeAnalysis entity = new ResumeAnalysis();
        entity.setResumeId(resume.getId());
        entity.setSourceHash(sourceHash);
        entity.setStatus(ResumeAnalysis.STATUS_SUCCESS);
        entity.setModelName(result.modelName());
        // 显式设置创建时间：MP 的 insert 不会回读数据库默认值（同 ResumeService.upload）
        entity.setCreatedAt(LocalDateTime.now());
        applyOutput(entity, result.value());

        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            entity.setId(null);
            entity.setVersionNo(nextVersionNo(resume.getId()));
            try {
                resumeAnalysisMapper.insert(entity);
                log.info("简历分析已落库：resumeId={} analysisId={} versionNo={} model={} 耗时={}ms",
                        resume.getId(), entity.getId(), entity.getVersionNo(),
                        result.modelName(), result.elapsedMillis());
                return toDetail(entity);
            }
            catch (DuplicateKeyException ex) {
                // uk_resume_analysis_version 兜底：并发下另一个请求已占用该版本号
                log.warn("分析版本号并发冲突，重试：resumeId={} versionNo={} 第{}次",
                        resume.getId(), entity.getVersionNo(), attempt);
            }
        }
        // 重试耗尽：简历文本入库后不可变，库中已有结果与本次同源，返回最新已有结果（API 4.5）
        ResumeAnalysis fallback = findLatestSuccess(resume.getId());
        if (fallback != null) {
            return toDetail(fallback);
        }
        throw new BusinessException(50000, 500, "分析结果保存失败，请稍后重试");
    }

    // ---------------------------------------------------------------- 查询与工具

    /**
     * 最新成功分析（重试兜底、4.6 与 M6/M8 共用同一查询路径）。
     *
     * public：M6 创建匹配与 M8 的 Agent 工具读取分析依据时复用同一查询，
     * 避免各调用方自行拼 Wrapper 造成口径漂移。
     */
    public ResumeAnalysis findLatestSuccess(Long resumeId) {
        return resumeAnalysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId)
                .eq(ResumeAnalysis::getStatus, ResumeAnalysis.STATUS_SUCCESS)
                .orderByDesc(ResumeAnalysis::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 下一版本号。**不带 status 过滤**：唯一索引覆盖所有状态，
     * 取 MAX 可避免历史状态行的版本号被重复占用。
     */
    private int nextVersionNo(Long resumeId) {
        ResumeAnalysis latest = resumeAnalysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .select(ResumeAnalysis::getVersionNo)
                .eq(ResumeAnalysis::getResumeId, resumeId)
                .orderByDesc(ResumeAnalysis::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private static void applyOutput(ResumeAnalysis entity, ResumeAnalysisAiOutput output) {
        entity.setSummary(output.getSummary());
        entity.setSkills(output.getSkills());
        entity.setEducation(output.getEducation());
        entity.setProjects(output.getProjects());
        entity.setExperience(output.getExperience());
        entity.setAdvantages(output.getAdvantages());
        entity.setWeaknesses(output.getWeaknesses());
        entity.setSuggestions(output.getSuggestions());
    }

    private static ResumeAnalysisDetail toDetail(ResumeAnalysis entity) {
        ResumeAnalysisDetail detail = new ResumeAnalysisDetail();
        detail.setAnalysisId(entity.getId());
        detail.setResumeId(entity.getResumeId());
        detail.setVersionNo(entity.getVersionNo());
        detail.setStatus(entity.getStatus());
        detail.setSummary(entity.getSummary());
        detail.setSkills(entity.getSkills());
        detail.setEducation(entity.getEducation());
        detail.setProjects(entity.getProjects());
        detail.setExperience(entity.getExperience());
        detail.setAdvantages(entity.getAdvantages());
        detail.setWeaknesses(entity.getWeaknesses());
        detail.setSuggestions(entity.getSuggestions());
        detail.setModelName(entity.getModelName());
        detail.setCreatedAt(entity.getCreatedAt());
        return detail;
    }

}
