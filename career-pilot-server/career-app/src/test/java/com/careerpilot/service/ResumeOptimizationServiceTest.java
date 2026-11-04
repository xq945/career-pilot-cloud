package com.careerpilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.Hashing;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.OptimizedResumeFile;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;
import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.dto.ai.ResumeAnalysisAiOutput;
import com.careerpilot.dto.ai.ResumeOptimizationAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.config.MatchScoringProperties;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.service.optimization.OptimizationStreamProducer;
import com.careerpilot.service.optimization.OptimizationTaskRunner;
import com.careerpilot.service.optimization.OptimizationTaskStateService;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.ai.ResumeAiService;
import com.careerpilot.service.ai.ResumeOptimizationAiService;

/**
 * 简历定向优化业务编排的单测（《测试与验收方案》2.1 优化行）。
 *
 * 覆盖四条硬约束：缺分析/过期 → 400（不自动补齐）、失败不入库（模型调用先于 INSERT）、
 * **保存编辑不覆盖模型原稿**（旧稿进历史、上限 10 版）、重新评估**复用同一份岗位分析**。
 */
@ExtendWith(MockitoExtension.class)
class ResumeOptimizationServiceTest {

    private static final Long USER_ID = 7L;

    private static final Long RESUME_ID = 101L;

    private static final Long JOB_ID = 201L;

    private static final String DESCRIPTION = "岗位职责：参与核心业务开发\n任职要求：熟悉 Java";

    @Mock
    private ResumeOptimizationMapper optimizationMapper;

    @Mock
    private MatchRecordMapper matchRecordMapper;

    @Mock
    private ResumeService resumeService;

    @Mock
    private JobService jobService;

    @Mock
    private ResumeAnalysisService resumeAnalysisService;

    @Mock
    private JobAnalysisService jobAnalysisService;

    @Mock
    private MatchService matchService;

    @Mock
    private MatchScoringService matchScoringService;

    @Mock
    private ResumeOptimizationAiService resumeOptimizationAiService;

    @Mock
    private ResumeAiService resumeAiService;

    // M16：任务编排三件套（执行体 / 状态机 / 生产者）
    @Mock
    private OptimizationTaskRunner taskRunner;

    @Mock
    private OptimizationTaskStateService stateService;

    @Mock
    private OptimizationStreamProducer streamProducer;

    @Mock
    private ObjectProvider<OptimizationStreamProducer> streamProducerProvider;

    @Mock
    private MatchScoringProperties matchScoringProperties;

    private LocalResumeOptimizationService service;

    /** insert 时写入的实体引用（create 末尾 selectById 回读用） */
    private ResumeOptimization insertedRecord;

    @BeforeEach
    void setUp() {
        service = new LocalResumeOptimizationService(optimizationMapper, matchRecordMapper,
                resumeService, jobService, resumeAnalysisService,
                jobAnalysisService, matchService, matchScoringService, resumeOptimizationAiService, resumeAiService,
                taskRunner, stateService, streamProducerProvider, matchScoringProperties);
        lenient().when(matchScoringProperties.ruleVersion()).thenReturn("v1");
    }

    // ---------------------------------------------------------------- 创建前置校验

    @Test
    void create_missingResumeAnalysis_throwsBadRequestWithoutModelCall() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.create(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先完成简历分析");

        verify(matchService, never()).create(any(), any(), any(), any());
    }

    @Test
    void create_staleJobAnalysis_throwsBadRequest() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis());
        // source_hash 与当前描述不一致 → 过期
        JobAnalysis stale = jobAnalysis();
        stale.setSourceHash(Hashing.sha256Hex("另一份描述"));
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(stale);

        assertThatThrownBy(() -> service.create(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("岗位描述已更新");

        verify(matchService, never()).create(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 创建成功路径

    @Test
    void create_success_insertsPendingTaskAndEnqueues() {
        stubHappyPath();
        when(streamProducerProvider.getIfAvailable()).thenReturn(streamProducer);
        when(streamProducer.send(any())).thenReturn(true);
        ArgumentCaptor<ResumeOptimization> inserted = ArgumentCaptor.forClass(ResumeOptimization.class);

        ResumeOptimizationDetail detail = service.create(USER_ID, request());

        verify(optimizationMapper).insert(inserted.capture());
        ResumeOptimization record = inserted.getValue();
        assertThat(record.getVersionNo()).isEqualTo(1);
        assertThat(record.getStatus()).isEqualTo(ResumeOptimization.STATUS_PENDING);
        assertThat(record.getMatchRecordId()).isNull();      // 匹配尚未发生（M16 异步语义）
        assertThat(record.getOptimizedContent()).isNull();
        assertThat(record.getModelName()).isNull();

        // 异步路径：模型与执行体都不在请求线程内触达
        verifyNoInteractions(matchService, matchScoringService, resumeOptimizationAiService, taskRunner);
        verify(streamProducer).send(new OptimizationStreamProducer.OptimizationTask(301L, USER_ID));

        // 详情：未完成态只装配来源与任务控制字段，产物字段为空
        assertThat(detail.getId()).isEqualTo(301L);
        assertThat(detail.getStatus()).isEqualTo(ResumeOptimization.STATUS_PENDING);
        assertThat(detail.getResumeName()).isEqualTo("Java后端简历");
        assertThat(detail.getJobName()).isEqualTo("Java 后端开发实习生");
        assertThat(detail.getResumeVersionNo()).isNull();
        assertThat(detail.getOptimizedContent()).isNull();
    }

    @Test
    void create_enqueueUnavailable_fallsBackToSyncExecution() {
        stubHappyPath();
        // 异步未启用（enabled=false）或入队失败 → 降级同步执行（不把任务静默丢失）
        when(streamProducerProvider.getIfAvailable()).thenReturn(null);
        when(stateService.markProcessing(301L)).thenReturn("attempt-1");

        service.create(USER_ID, request());

        verify(stateService).markProcessing(301L);
        verify(taskRunner).run(301L, "attempt-1");
    }

    @Test
    void create_syncBusinessFailure_carriesOptimizationId() {
        stubSyncFailurePath();
        doThrow(BusinessException.aiUnavailable("大模型服务暂时不可用，请稍后重试"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50200);
        assertThat(ex.getData()).isEqualTo(Map.of("optimizationId", 301L));
        verify(stateService).fail(eq(301L), eq("attempt-1"), eq("大模型服务暂时不可用，请稍后重试"), nullable(String.class));
    }

    @Test
    void create_syncTaskValidationFailure_keeps400AndCarriesOptimizationId() {
        stubSyncFailurePath();
        doThrow(BusinessException.badRequest("岗位分析已失效或已过期，请重新创建优化"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        // 执行期复核失败发生在任务落库之后：400 仍保留，但不能丢 optimizationId
        assertThat(ex.getCode()).isEqualTo(40000);
        assertThat(ex.getData()).isEqualTo(Map.of("optimizationId", 301L));
    }

    @Test
    void create_syncOverlongRejection_recordsFailureWithoutSuccessFields() {
        stubSyncFailurePath();
        doThrow(BusinessException.aiInvalidOutput(
                "生成的优化稿超过长度限制（8000 字符），未保存为有效优化结果"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50201);
        assertThat(ex.getMessage()).contains("超过长度限制");
        verify(stateService).fail(eq(301L), eq("attempt-1"), contains("超过长度限制"), nullable(String.class));
        // 被拒绝的正文不得进入成功落库路径
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_syncChangeInformationLoss_recordsFailureWithoutSuccessFields() {
        stubSyncFailurePath();
        doThrow(BusinessException.aiInvalidOutput(
                "变更记录清洗存在信息损失（输入 2 条，保留 1 条，措辞守卫 1），无法证明正文改动已完整核对"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50201);
        assertThat(ex.getMessage()).contains("信息损失");
        verify(stateService).fail(eq(301L), eq("attempt-1"), contains("信息损失"), nullable(String.class));
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_syncNumericFactFailure_recordsFailureWithoutSuccessFields() {
        stubSyncFailurePath();
        doThrow(BusinessException.aiInvalidOutput(
                "优化结果未通过事实校验，未保存：优化稿第 1 处数值与原文的单位、指标、对象或上下文不一致"
                        + "（可疑数字篡改，需人工确认）"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50201);
        assertThat(ex.getMessage()).contains("事实校验");
        verify(stateService).fail(eq(301L), eq("attempt-1"), contains("事实校验"), nullable(String.class));
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_syncCoverageFailure_recordsFailureWithoutSuccessFields() {
        stubSyncFailurePath();
        doThrow(BusinessException.aiInvalidOutput(
                "优化结果未通过事实校验，未保存：changes[0] 涉及跨章节移动事实（当前版本不支持）"))
                .when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50201);
        assertThat(ex.getMessage()).contains("跨章节");
        verify(stateService).fail(eq(301L), eq("attempt-1"), contains("跨章节"), nullable(String.class));
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_syncUnexpectedFailure_wrapsAs500WithOptimizationId() {
        stubSyncFailurePath();
        doThrow(new IllegalStateException("boom")).when(taskRunner).run(301L, "attempt-1");

        BusinessException ex = catchThrowableOfType(
                () -> service.create(USER_ID, request()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(50000);
        assertThat(ex.getMessage()).isEqualTo("任务执行失败，请稍后重试");
        assertThat(ex.getData()).isEqualTo(Map.of("optimizationId", 301L));
        verify(stateService).fail(eq(301L), eq("attempt-1"), eq("boom"), nullable(String.class));
    }

    @Test
    void retry_onlyFailedTaskCanBeRetried() {
        ResumeOptimization record = storedRecord();
        record.setStatus(ResumeOptimization.STATUS_SUCCESS);
        when(optimizationMapper.selectOne(any())).thenReturn(record);

        assertThatThrownBy(() -> service.retry(USER_ID, 301L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅失败的任务可以重试");
    }

    // ---------------------------------------------------------------- 列表（O-09）

    @Test
    void list_allPendingWithoutMatchRecord_returnsTaskRows() {
        assertListWithoutMatchRecords(ResumeOptimization.STATUS_PENDING);
    }

    @Test
    void list_allProcessingWithoutMatchRecord_returnsTaskRows() {
        assertListWithoutMatchRecords(ResumeOptimization.STATUS_PROCESSING);
    }

    @Test
    void list_allFailedWithoutMatchRecord_returnsTaskRows() {
        assertListWithoutMatchRecords(ResumeOptimization.STATUS_FAILED);
    }

    @Test
    void list_mixedStates_keepsMatchDataForCompletedRows() {
        ResumeOptimization success = taskRecord(301L, ResumeOptimization.STATUS_SUCCESS, 501L);
        success.setScoreAfter(new BigDecimal("80.00"));
        ResumeOptimization failed = taskRecord(302L, ResumeOptimization.STATUS_FAILED, null);
        stubListQueries(List.of(success, failed), List.of(matchRecord()));

        PageResult<ResumeOptimizationView> result = service.list(USER_ID, new PageQuery());

        assertThat(result.getRecords()).hasSize(2);
        ResumeOptimizationView completed = result.getRecords().get(0);
        assertThat(completed.getStatus()).isEqualTo(ResumeOptimization.STATUS_SUCCESS);
        assertThat(completed.getMatchScore()).isEqualTo(75.75);
        assertThat(completed.getScoreAfter()).isEqualTo(80.0);
        ResumeOptimizationView stillFailed = result.getRecords().get(1);
        assertThat(stillFailed.getStatus()).isEqualTo(ResumeOptimization.STATUS_FAILED);
        assertThat(stillFailed.getMatchScore()).isNull();
        assertThat(stillFailed.getScoreAfter()).isNull();
    }

    @Test
    void list_successRecord_displaysMatchScoresAndNames() {
        ResumeOptimization success = taskRecord(301L, ResumeOptimization.STATUS_SUCCESS, 501L);
        success.setScoreAfter(new BigDecimal("80.00"));
        stubListQueries(List.of(success), List.of(matchRecord()));

        PageResult<ResumeOptimizationView> result = service.list(USER_ID, new PageQuery());

        ResumeOptimizationView view = result.getRecords().get(0);
        assertThat(view.getMatchScore()).isEqualTo(75.75);
        assertThat(view.getScoreAfter()).isEqualTo(80.0);
        assertThat(view.getResumeName()).isEqualTo("Java后端简历");
        assertThat(view.getJobName()).isEqualTo("Java 后端开发实习生");
        assertThat(view.getCompanyName()).isEqualTo("示例科技");
    }

    // ---------------------------------------------------------------- 保存编辑稿

    @Test
    void saveContent_keepsModelOriginalAndPushesOldDraftToHistory() {
        ResumeOptimization record = storedRecord();
        record.setEditedContent("用户的第一版");
        when(optimizationMapper.selectOne(any())).thenReturn(record);
        stubDetailDependencies(record);

        service.saveContent(USER_ID, 301L, "用户的第二版");

        ArgumentCaptor<ResumeOptimization> updated = ArgumentCaptor.forClass(ResumeOptimization.class);
        verify(optimizationMapper).updateById(updated.capture());
        ResumeOptimization saved = updated.getValue();
        assertThat(saved.getEditedContent()).isEqualTo("用户的第二版");
        assertThat(saved.getOptimizedContent()).isEqualTo("## 技能\n- Java（模型原稿）");
        assertThat(saved.getEditHistory()).hasSize(1);
        assertThat(saved.getEditHistory().get(0).getContent()).isEqualTo("用户的第一版");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void saveContent_sameContent_doesNotWriteOrCreateHistory() {
        ResumeOptimization record = storedRecord();
        record.setEditedContent("与当前一致");
        when(optimizationMapper.selectOne(any())).thenReturn(record);
        stubDetailDependencies(record);

        service.saveContent(USER_ID, 301L, "  与当前一致  "); // strip 后相同

        verify(optimizationMapper, never()).updateById(any(ResumeOptimization.class));
    }

    @Test
    void saveContent_historyLimitedToTen() {
        ResumeOptimization record = storedRecord();
        record.setEditedContent("当前稿");
        List<com.careerpilot.dto.EditSnapshot> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new com.careerpilot.dto.EditSnapshot("旧稿 " + i, LocalDateTime.now()));
        }
        record.setEditHistory(history);
        when(optimizationMapper.selectOne(any())).thenReturn(record);
        stubDetailDependencies(record);

        service.saveContent(USER_ID, 301L, "新稿");

        ArgumentCaptor<ResumeOptimization> updated = ArgumentCaptor.forClass(ResumeOptimization.class);
        verify(optimizationMapper).updateById(updated.capture());
        List<com.careerpilot.dto.EditSnapshot> saved = updated.getValue().getEditHistory();
        assertThat(saved).hasSize(10);
        assertThat(saved.get(9).getContent()).isEqualTo("当前稿");
        assertThat(saved.get(0).getContent()).isEqualTo("旧稿 1");
    }

    // ---------------------------------------------------------------- 重新评估

    @Test
    void evaluate_reusesOriginalJobAnalysisAndWritesScoreAfter() {
        ResumeOptimization record = storedRecord();
        record.setEditedContent("用户最终稿");
        when(optimizationMapper.selectOne(any())).thenReturn(record);
        // 评估复用优化前那条 match_record 的岗位分析（同一份，保证前后可比）
        when(matchRecordMapper.selectById(501L)).thenReturn(matchRecord());
        when(jobAnalysisService.getAnalysisById(11L)).thenReturn(jobAnalysis());
        when(resumeAiService.analyze("用户最终稿"))
                .thenReturn(new AiCallResult<>(new ResumeAnalysisAiOutput(), "test-model", 10L, 1, 1, 2));
        when(matchScoringService.score(any(), any())).thenReturn(scoring());
        stubDetailDependencies(record);

        ResumeOptimizationDetail detail = service.evaluate(USER_ID, 301L);

        ArgumentCaptor<ResumeOptimization> updated = ArgumentCaptor.forClass(ResumeOptimization.class);
        verify(optimizationMapper).updateById(updated.capture());
        ResumeOptimization saved = updated.getValue();
        assertThat(saved.getScoreAfter()).isEqualByComparingTo("75.75");
        assertThat(saved.getDimensionScoresAfter().getRuleVersion()).isEqualTo("v1");
        assertThat(saved.getEvaluatedAt()).isNotNull();
        assertThat(detail.getScoreAfter()).isEqualTo(75.75);
    }

    // ---------------------------------------------------------------- 导出

    @Test
    void readExport_prefersEditedContentAndBuildsFileName() {
        ResumeOptimization record = storedRecord();
        record.setEditedContent("用户最终稿");
        when(optimizationMapper.selectOne(any())).thenReturn(record);
        when(resumeService.getResumeById(RESUME_ID)).thenReturn(resume());
        when(jobService.getJobById(JOB_ID)).thenReturn(job());

        OptimizedResumeFile file = service.readExport(USER_ID, 301L);

        assertThat(file.getContent()).isEqualTo("用户最终稿");
        assertThat(file.getFileName()).isEqualTo("Java后端简历-Java 后端开发实习生-优化稿.docx");
    }

    @Test
    void detail_otherUsersRecord_throws404() {
        when(optimizationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(USER_ID, 301L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("优化记录不存在");
    }

    // ---------------------------------------------------------------- 辅助

    /** O-09：当页全部为无 match_record 的任务行时，列表仍能正常装配 */
    private void assertListWithoutMatchRecords(String status) {
        stubListQueries(List.of(
                taskRecord(301L, status, null),
                taskRecord(302L, status, null)), List.of());

        PageResult<ResumeOptimizationView> result = service.list(USER_ID, new PageQuery());

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).allSatisfy(view -> {
            assertThat(view.getStatus()).isEqualTo(status);
            assertThat(view.getMatchScore()).isNull();
            assertThat(view.getScoreAfter()).isNull();
            assertThat(view.getResumeName()).isEqualTo("Java后端简历");
            assertThat(view.getJobName()).isEqualTo("Java 后端开发实习生");
        });
    }

    private void stubListQueries(List<ResumeOptimization> records, List<MatchRecord> matches) {
        when(optimizationMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ResumeOptimization> page = invocation.getArgument(0);
            page.setRecords(records);
            page.setTotal(records.size());
            return page;
        });
        when(resumeService.listResumesByIds(any())).thenReturn(List.of(resume()));
        when(jobService.listJobsByIds(any())).thenReturn(List.of(job()));
        if (!matches.isEmpty()) {
            when(matchRecordMapper.selectList(any())).thenReturn(matches);
        }
    }

    /** 同步降级失败路径：只 stub 走到异常前所需的依赖，避免 Mockito 严格模式下的多余 stub */
    private void stubSyncFailurePath() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis());
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis());
        when(optimizationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ResumeOptimization record = invocation.getArgument(0);
            record.setId(301L);
            return 1;
        }).when(optimizationMapper).insert(any(ResumeOptimization.class));
        when(streamProducerProvider.getIfAvailable()).thenReturn(null);
        when(stateService.markProcessing(301L)).thenReturn("attempt-1");
    }

    private static ResumeOptimization taskRecord(Long id, String status, Long matchRecordId) {
        ResumeOptimization record = new ResumeOptimization();
        record.setId(id);
        record.setUserId(USER_ID);
        record.setResumeId(RESUME_ID);
        record.setJobId(JOB_ID);
        record.setMatchRecordId(matchRecordId);
        record.setVersionNo(1);
        record.setStatus(status);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    private void stubHappyPath() {
        when(resumeService.getOwnedResume(USER_ID, RESUME_ID)).thenReturn(resume());
        when(jobService.getOwnedJob(USER_ID, JOB_ID)).thenReturn(job());
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis());
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis());
        // 版本号查询（首次创建 → MAX 为空）
        when(optimizationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ResumeOptimization record = invocation.getArgument(0);
            record.setId(301L);
            insertedRecord = record;   // 保存引用：create 末尾的 selectById 回读
            return 1;
        }).when(optimizationMapper).insert(any(ResumeOptimization.class));
        // M16：任务行回读 + 未完成态的详情来源查询（resume/job）
        when(optimizationMapper.selectById(301L)).thenAnswer(invocation -> insertedRecord);
        when(resumeService.getResumeById(RESUME_ID)).thenReturn(resume());
        when(jobService.getJobById(JOB_ID)).thenReturn(job());
    }

    /** toDetail 的关联查询（stubHappyPath 与各写路径共用） */
    private void stubDetailDependencies(ResumeOptimization ignored) {
        when(matchRecordMapper.selectById(501L)).thenReturn(matchRecord());
        when(jobAnalysisService.getAnalysisById(11L)).thenReturn(jobAnalysis());
        when(resumeAnalysisService.getAnalysisById(21L)).thenReturn(resumeAnalysis());
        when(resumeService.getResumeById(RESUME_ID)).thenReturn(resume());
        when(jobService.getJobById(JOB_ID)).thenReturn(job());
    }

    private static ResumeOptimizationCreateRequest request() {
        ResumeOptimizationCreateRequest request = new ResumeOptimizationCreateRequest();
        request.setResumeId(RESUME_ID);
        request.setJobId(JOB_ID);
        return request;
    }

    private static Resume resume() {
        Resume resume = new Resume();
        resume.setId(RESUME_ID);
        resume.setUserId(USER_ID);
        resume.setName("Java后端简历");
        resume.setContent("张三 / Java 后端开发");
        return resume;
    }

    private static Job job() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setUserId(USER_ID);
        job.setJobName("Java 后端开发实习生");
        job.setCompanyName("示例科技");
        job.setDescription(DESCRIPTION);
        return job;
    }

    private static ResumeAnalysis resumeAnalysis() {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setId(21L);
        analysis.setResumeId(RESUME_ID);
        analysis.setVersionNo(2);
        analysis.setAdvantages(List.of("技能组合与岗位相关"));
        return analysis;
    }

    private static JobAnalysis jobAnalysis() {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setId(11L);
        analysis.setJobId(JOB_ID);
        analysis.setVersionNo(1);
        analysis.setSourceHash(Hashing.sha256Hex(DESCRIPTION));
        analysis.setRequiredSkills(List.of("Java"));
        return analysis;
    }

    private static MatchRecord matchRecord() {
        MatchRecord record = new MatchRecord();
        record.setId(501L);
        record.setResumeAnalysisId(21L);
        record.setJobAnalysisId(11L);
        record.setMatchScore(new BigDecimal("75.75"));
        record.setDimensionScores(dimensionScores());
        record.setMatchedItems(List.of());
        record.setPartialItems(List.of());
        record.setMissingItems(List.of());
        return record;
    }

    private static MatchDetail matchDetail() {
        MatchDetail detail = new MatchDetail();
        detail.setId(501L);
        detail.setMatchScore(75.75);
        return detail;
    }

    private static MatchScoringResult scoring() {
        return new MatchScoringResult(new BigDecimal("75.75"), dimensionScores(), List.of());
    }

    private static MatchDimensionScores dimensionScores() {
        List<MatchDimensionScore> items = List.of(
                new MatchDimensionScore("requiredSkills", "必备技能", 45, 80));
        return new MatchDimensionScores("v1", items);
    }

    private static AiCallResult<ResumeOptimizationAiOutput> generated() {
        ResumeOptimizationAiOutput output = new ResumeOptimizationAiOutput();
        output.setOptimizedContent("## 技能\n- Java（模型原稿）");
        ChangeItem item = new ChangeItem();
        item.setSection("技能");
        item.setType("KEYWORD");
        item.setReason("对齐岗位关键词");
        item.setBefore("Java");
        item.setAfter("Java（项目主力语言）");
        output.setChanges(List.of(item));
        output.setGapQuestions(List.of("项目的 QPS 是多少？"));
        return new AiCallResult<>(output, "test-model", 100L, 1, 1, 2);
    }

    private static ResumeOptimization storedRecord() {
        ResumeOptimization record = new ResumeOptimization();
        record.setId(301L);
        record.setUserId(USER_ID);
        record.setResumeId(RESUME_ID);
        record.setJobId(JOB_ID);
        record.setMatchRecordId(501L);
        record.setVersionNo(1);
        record.setOptimizedContent("## 技能\n- Java（模型原稿）");
        record.setChanges(List.of());
        record.setGapQuestions(List.of());
        record.setStatus(ResumeOptimization.STATUS_SUCCESS);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

}
