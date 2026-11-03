package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.Hashing;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.MatchView;
import com.careerpilot.dto.ai.MatchExplanationAiOutput;
import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.service.ai.MatchAiService;

/**
 * 匹配编排的护栏（《API接口设计》6.1–6.3）。
 *
 * 重点断言三类不可回退的行为：**失败不入库**（模型异常 / 缺分析 / 过期拦截时绝不写 match_record）、
 * **落库分数只来自规则计算**、**关联查询不过滤 status**（源资源删除后名称仍显示）。
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    private static final Long USER_ID = 1L;

    private static final Long RESUME_ID = 11L;

    private static final Long JOB_ID = 21L;

    private static final String DESCRIPTION = "负责后端系统开发，要求熟悉 Java 与 MySQL";

    @Mock
    private MatchRecordMapper matchRecordMapper;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private JobMapper jobMapper;

    @Mock
    private ResumeAnalysisMapper resumeAnalysisMapper;

    @Mock
    private JobAnalysisMapper jobAnalysisMapper;

    @Mock
    private ResumeService resumeService;

    @Mock
    private JobService jobService;

    @Mock
    private ResumeAnalysisService resumeAnalysisService;

    @Mock
    private JobAnalysisService jobAnalysisService;

    @Mock
    private MatchScoringService matchScoringService;

    @Mock
    private MatchAiService matchAiService;

    @InjectMocks
    private MatchService matchService;

    // ---------------------------------------------------------------- 创建：主路径

    @Test
    void create_persistsRuleScoreAndReturnsInlineDetail() {
        Job job = job();
        JobAnalysis jobAnalysis = jobAnalysis(Hashing.sha256Hex(DESCRIPTION));
        ResumeAnalysis resumeAnalysis = resumeAnalysis();
        MatchScoringResult scoring = scoring();
        MatchExplanationAiOutput explanation = explanation();
        stubHappyPath(job, jobAnalysis, resumeAnalysis, scoring, explanation);

        MatchDetail detail = matchService.create(USER_ID, request());

        ArgumentCaptor<MatchRecord> captor = ArgumentCaptor.forClass(MatchRecord.class);
        verify(matchRecordMapper).insert(captor.capture());
        MatchRecord saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(RESUME_ID, saved.getResumeId());
        assertEquals(JOB_ID, saved.getJobId());
        assertEquals(31L, saved.getResumeAnalysisId());
        assertEquals(41L, saved.getJobAnalysisId());
        // 落库分数只来自规则计算（模型输出中不存在分数字段）
        assertEquals(0, scoring.totalScore().compareTo(saved.getMatchScore()));
        assertEquals(0, saved.getCreatedAt().getNano());

        assertEquals(75.75, detail.getMatchScore(), 0.001);
        assertEquals("Java后端简历", detail.getResumeName());
        assertEquals("Java后端开发实习生", detail.getJobName());
        assertEquals("示例科技", detail.getCompanyName());
        assertEquals(1, detail.getResumeVersionNo());
        assertEquals(2, detail.getJobVersionNo());
    }

    // ---------------------------------------------------------------- 创建：失败矩阵（不得入库）

    @Test
    void create_missingResumeAnalysisGives400NamingResume() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> matchService.create(USER_ID, request()));

        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("简历分析"), "文案必须指出缺哪一项：" + ex.getMessage());
        verifyNoInteractions(matchAiService, matchScoringService);
        verify(matchRecordMapper, never()).insert(any(MatchRecord.class));
    }

    @Test
    void create_missingJobAnalysisGives400NamingJob() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis());
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> matchService.create(USER_ID, request()));

        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("岗位分析"), "文案必须指出缺哪一项：" + ex.getMessage());
        verifyNoInteractions(matchAiService);
        verify(matchRecordMapper, never()).insert(any(MatchRecord.class));
    }

    @Test
    void create_staleJobAnalysisGives400PointingToRerun() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis());
        // 分析依据的是旧描述 → stale（判定复用 JobAnalysisService.isStale 单点）
        when(jobAnalysisService.findLatestSuccess(JOB_ID))
                .thenReturn(jobAnalysis(Hashing.sha256Hex("旧版描述")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> matchService.create(USER_ID, request()));

        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("重新分析"), "文案必须指向先重新分析：" + ex.getMessage());
        verifyNoInteractions(matchAiService, matchScoringService);
        verify(matchRecordMapper, never()).insert(any(MatchRecord.class));
    }

    @Test
    void create_notOwnedResourcePropagates404WithoutTouchingModelOrDb() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID))
                .thenThrow(BusinessException.notFound("简历不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> matchService.create(USER_ID, request()));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(matchAiService, matchScoringService, matchRecordMapper,
                resumeAnalysisService, jobAnalysisService);
    }

    @Test
    void create_modelFailureDoesNotPersist() {
        Job job = job();
        JobAnalysis jobAnalysis = jobAnalysis(Hashing.sha256Hex(DESCRIPTION));
        ResumeAnalysis resumeAnalysis = resumeAnalysis();
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job);
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis);
        MatchScoringResult scoring = scoring();
        when(matchScoringService.score(jobAnalysis, resumeAnalysis)).thenReturn(scoring);
        when(matchAiService.explain(jobAnalysis, resumeAnalysis, scoring))
                .thenThrow(BusinessException.aiUnavailable("大模型服务暂时不可用，请稍后重试"));

        assertThrows(BusinessException.class, () -> matchService.create(USER_ID, request()));

        verify(matchRecordMapper, never()).insert(any(MatchRecord.class));
    }

    // ---------------------------------------------------------------- 创建：重复匹配与结构校验

    @Test
    void create_repeatedMatchingInsertsNewRecordEachTime() {
        Job job = job();
        JobAnalysis jobAnalysis = jobAnalysis(Hashing.sha256Hex(DESCRIPTION));
        ResumeAnalysis resumeAnalysis = resumeAnalysis();
        stubHappyPath(job, jobAnalysis, resumeAnalysis, scoring(), explanation());

        matchService.create(USER_ID, request());
        matchService.create(USER_ID, request());

        // 每次调用创建新记录（保留历史），不覆盖也不去重
        verify(matchRecordMapper, times(2)).insert(any(MatchRecord.class));
    }

    @Test
    void create_sanitizesDirtyAnalysisBeforeScoring() {
        Job job = job();
        JobAnalysis jobAnalysis = jobAnalysis(Hashing.sha256Hex(DESCRIPTION));
        ResumeAnalysis dirty = resumeAnalysis();
        SkillItem ok = new SkillItem();
        ok.setName("Java");
        SkillItem missingName = new SkillItem();
        dirty.setSkills(new ArrayList<>(List.of(ok, missingName)));
        stubHappyPath(job, jobAnalysis, dirty, scoring(), explanation());

        matchService.create(USER_ID, request());

        ArgumentCaptor<ResumeAnalysis> captor = ArgumentCaptor.forClass(ResumeAnalysis.class);
        verify(matchScoringService).score(any(), captor.capture());
        assertEquals(1, captor.getValue().getSkills().size(), "缺 name 的技能项不得进入计分");
    }

    // ---------------------------------------------------------------- 列表与详情

    @Test
    void list_inlinesNamesEvenForLogicallyDeletedSources() {
        MatchRecord record = record();
        Page<MatchRecord> page = new Page<>(1, 10);
        page.setRecords(List.of(record));
        page.setTotal(1);
        when(matchRecordMapper.selectPage(any(), any())).thenReturn(page);

        // 源资源已逻辑删除：关联查询不过滤 status，名称仍须显示（《API接口设计》6.2）
        Resume deletedResume = resume();
        deletedResume.setStatus(Resume.STATUS_DELETED);
        Job deletedJob = job();
        deletedJob.setStatus(Job.STATUS_DELETED);
        when(resumeMapper.selectList(any())).thenReturn(List.of(deletedResume));
        when(jobMapper.selectList(any())).thenReturn(List.of(deletedJob));
        when(resumeAnalysisMapper.selectList(any())).thenReturn(List.of(resumeAnalysis()));
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(jobAnalysis("hash")));

        PageResult<MatchView> result = matchService.list(USER_ID, new PageQuery());

        assertEquals(1, result.getRecords().size());
        MatchView view = result.getRecords().get(0);
        assertEquals("Java后端简历", view.getResumeName());
        assertEquals("Java后端开发实习生", view.getJobName());
        assertEquals(1, view.getResumeVersionNo());
        assertEquals(2, view.getJobVersionNo());
    }

    @Test
    void detail_notFoundOrNotOwnedGives404() {
        when(matchRecordMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> matchService.detail(USER_ID, 99L));

        assertEquals(40400, ex.getCode());
    }

    // ---------------------------------------------------------------- 辅助

    private void stubHappyPath(Job job, JobAnalysis jobAnalysis, ResumeAnalysis resumeAnalysis,
                               MatchScoringResult scoring, MatchExplanationAiOutput explanation) {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job);
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis);
        when(matchScoringService.score(jobAnalysis, resumeAnalysis)).thenReturn(scoring);
        when(matchAiService.explain(jobAnalysis, resumeAnalysis, scoring))
                .thenReturn(new AiCallResult<>(explanation, "test-model", 100L, 1, 1, 2));
    }

    private static MatchCreateRequest request() {
        MatchCreateRequest request = new MatchCreateRequest();
        request.setResumeId(RESUME_ID);
        request.setJobId(JOB_ID);
        return request;
    }

    private static Resume resume() {
        Resume resume = new Resume();
        resume.setId(RESUME_ID);
        resume.setUserId(USER_ID);
        resume.setName("Java后端简历");
        resume.setStatus(Resume.STATUS_ACTIVE);
        return resume;
    }

    private static Job job() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setUserId(USER_ID);
        job.setJobName("Java后端开发实习生");
        job.setCompanyName("示例科技");
        job.setDescription(DESCRIPTION);
        job.setStatus(Job.STATUS_ACTIVE);
        return job;
    }

    private static ResumeAnalysis resumeAnalysis() {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setId(31L);
        analysis.setResumeId(RESUME_ID);
        analysis.setVersionNo(1);
        analysis.setStatus(ResumeAnalysis.STATUS_SUCCESS);
        return analysis;
    }

    private static JobAnalysis jobAnalysis(String sourceHash) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setId(41L);
        analysis.setJobId(JOB_ID);
        analysis.setVersionNo(2);
        analysis.setStatus(JobAnalysis.STATUS_SUCCESS);
        analysis.setSourceHash(sourceHash);
        return analysis;
    }

    private static MatchRecord record() {
        MatchRecord record = new MatchRecord();
        record.setId(51L);
        record.setUserId(USER_ID);
        record.setResumeId(RESUME_ID);
        record.setJobId(JOB_ID);
        record.setResumeAnalysisId(31L);
        record.setJobAnalysisId(41L);
        record.setMatchScore(new BigDecimal("75.75"));
        record.setCreatedAt(LocalDateTime.now().withNano(0));
        return record;
    }

    private static MatchScoringResult scoring() {
        List<MatchDimensionScore> items = List.of(
                new MatchDimensionScore("requiredSkills", "必备技能", 45, 80),
                new MatchDimensionScore("projectRelevance", "项目经历相关性", 25, 75),
                new MatchDimensionScore("experience", "工作/实习经历", 15, 70),
                new MatchDimensionScore("education", "学历与经验要求", 10, 80),
                new MatchDimensionScore("preferredSkills", "加分技能", 5, 50));
        return new MatchScoringResult(new BigDecimal("75.75"),
                new MatchDimensionScores("v1", items), List.of());
    }

    private static MatchExplanationAiOutput explanation() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMatchedItems(new ArrayList<>(List.of(item("Java：项目中有使用", "requiredSkills"))));
        output.setPartialItems(new ArrayList<>());
        output.setMissingItems(new ArrayList<>(List.of(item("Docker：简历未体现相关证据", "requiredSkills"))));
        output.setSummary("整体匹配度中等偏上，存在工程实践经验的信息缺口。");
        output.setSuggestions(List.of("补充容器化部署经历"));
        return output;
    }

    private static MatchItem item(String text, String dimension) {
        MatchItem item = new MatchItem();
        item.setText(text);
        item.setDimension(dimension);
        return item;
    }

}
