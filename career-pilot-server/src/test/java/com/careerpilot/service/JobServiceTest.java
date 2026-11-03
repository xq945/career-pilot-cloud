package com.careerpilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;

/**
 * 岗位 CRUD 与 analyzed/stale 判定的单测（《开发顺序》M5 的「同构性检查」）。
 *
 * 本类是 M5 唯一覆盖**列表批量 stale 判定**的地方（三态：无分析 / 同源 / 异源），
 * 另覆盖详情的单条判定与 PUT 的内存装配（空公司名清空、updatedAt 显式刷新）。
 * 真库特有的行为（ON UPDATE 时间戳、唯一索引并发）不放这里——按《测试与验收方案》§1.1 放接口层。
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobMapper jobMapper;

    @Mock
    private JobAnalysisMapper jobAnalysisMapper;

    private JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobMapper, jobAnalysisMapper);
    }

    // ---------------------------------------------------------------- 列表：三态判定

    @Test
    void list_marksAnalyzedAndStalePerLatestSuccess() {
        Job noAnalysis = job(1L, "岗位A", "描述A");
        Job fresh = job(2L, "岗位B", "描述B");
        Job outdated = job(3L, "岗位C", "描述C-当前");
        doReturn(pageOf(noAnalysis, fresh, outdated)).when(jobMapper).selectPage(any(), any());
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(
                analysis(2L, 1, sha256("描述B")),           // 同源 → 不过期
                analysis(3L, 1, sha256("描述C-旧"))));      // 异源 → 过期

        PageResult<JobView> result = service.list(1L, new PageQuery());

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getRecords().get(0).isAnalyzed()).isFalse();
        assertThat(result.getRecords().get(0).isStale()).isFalse();    // 无分析 → stale 恒 false
        assertThat(result.getRecords().get(1).isAnalyzed()).isTrue();
        assertThat(result.getRecords().get(1).isStale()).isFalse();
        assertThat(result.getRecords().get(2).isAnalyzed()).isTrue();
        assertThat(result.getRecords().get(2).isStale()).isTrue();     // 《开发顺序》M5 验收标准 1
    }

    @Test
    void list_usesLatestVersionForStaleDecision() {
        Job jobB = job(2L, "岗位B", "描述B-当前");
        doReturn(pageOf(jobB)).when(jobMapper).selectPage(any(), any());
        // 同一岗位的两个版本：v1 异源、v2（最新，同源）→ 应取 v2 判定，为不过期
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(
                analysis(2L, 1, sha256("描述B-旧")),
                analysis(2L, 2, sha256("描述B-当前"))));

        PageResult<JobView> result = service.list(1L, new PageQuery());

        assertThat(result.getRecords().get(0).isStale()).isFalse();
    }

    @Test
    void list_emptyPage_returnsEmptyWithoutQueryingAnalyses() {
        doReturn(pageOf()).when(jobMapper).selectPage(any(), any());

        PageResult<JobView> result = service.list(1L, new PageQuery());

        assertThat(result.getRecords()).isEmpty();
        // 当页无记录时不发 IN 查询（空集短路）
        org.mockito.Mockito.verifyNoInteractions(jobAnalysisMapper);
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void detail_outdatedAnalysis_isAnalyzedAndStale() {
        when(jobMapper.selectOne(any())).thenReturn(job(5L, "岗位", "描述-当前"));
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 1, sha256("描述-旧")));

        JobDetail detail = service.detail(1L, 5L);

        assertThat(detail.isAnalyzed()).isTrue();
        assertThat(detail.isStale()).isTrue();
    }

    @Test
    void getOwnedJob_missingOrForeign_throws404() {
        when(jobMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40400));
    }

    // ---------------------------------------------------------------- 更新：全量语义与装配

    @Test
    void update_blankCompanyNameClearedAndUpdatedAtRefreshed() {
        Job existing = job(5L, "旧岗位名", "旧描述");
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 1, sha256("旧描述")));

        JobDetail detail = service.update(1L, 5L, request("   ", "新岗位名", "新描述"));

        assertThat(detail.getCompanyName()).isNull();                 // 空白 → 清空
        assertThat(detail.getJobName()).isEqualTo("新岗位名");
        assertThat(detail.getDescription()).isEqualTo("新描述");
        assertThat(detail.getUpdatedAt()).isNotNull();                // 内存装配的显式刷新值
        assertThat(detail.isAnalyzed()).isTrue();
        assertThat(detail.isStale()).isTrue();                        // 描述变化 → 立即过期
    }

    @Test
    void update_unchangedDescriptionStaysFresh() {
        Job existing = job(5L, "岗位", "描述");
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 1, sha256("描述")));

        JobDetail detail = service.update(1L, 5L, request("示例科技", "岗位", "描述"));

        assertThat(detail.getCompanyName()).isEqualTo("示例科技");
        assertThat(detail.isStale()).isFalse();                       // 描述未变 → 仍不过期
    }

    // ---------------------------------------------------------------- 投递链接（M19）

    @Test
    void create_applyUrlOptionalAndNormalized() {
        // 缺省（null）→ null
        assertThat(service.create(1L, request("示例科技", "岗位", "描述")).getApplyUrl()).isNull();
        // 全空白 → null（"清空"语义，同公司名）
        assertThat(service.create(1L, request("示例科技", "岗位", "描述", "   ")).getApplyUrl()).isNull();
        // 合法链接 → trim 后入库
        assertThat(service.create(1L, request("示例科技", "岗位", "描述", "  https://a.com/x  "))
                .getApplyUrl()).isEqualTo("https://a.com/x");
        // 协议大小写不敏感（HTTP:// 是合法写法）
        assertThat(service.create(1L, request("示例科技", "岗位", "描述", "HTTPS://a.com/x"))
                .getApplyUrl()).isEqualTo("HTTPS://a.com/x");
    }

    @Test
    void create_rejectsNonHttpApplyUrl() {
        // javascript: 伪协议若入库，前端 window.open 点击即执行脚本——必须在服务层拦下
        assertThatThrownBy(() -> service.create(1L, request("示例科技", "岗位", "描述", "javascript:alert(1)")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
        assertThatThrownBy(() -> service.create(1L, request("示例科技", "岗位", "描述", "ftp://a.com/x")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    @Test
    void list_exposesApplyUrlForApplyButton() {
        Job withUrl = job(1L, "岗位A", "描述A");
        withUrl.setApplyUrl("https://www.example.com/job/1");
        doReturn(pageOf(withUrl)).when(jobMapper).selectPage(any(), any());
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of());

        PageResult<JobView> result = service.list(1L, new PageQuery());

        // 列表项带 applyUrl，前端据此渲染「投递」按钮
        assertThat(result.getRecords().get(0).getApplyUrl()).isEqualTo("https://www.example.com/job/1");
    }

    @Test
    void update_applyUrlFollowsFullUpdateSemantics() {
        when(jobMapper.selectOne(any())).thenReturn(job(5L, "岗位", "描述"));

        // 全量更新：省略/空白 → 清空
        assertThat(service.update(1L, 5L, request("示例科技", "岗位", "描述", "   ")).getApplyUrl()).isNull();
        // 合法值 → 写入并回显
        assertThat(service.update(1L, 5L, request("示例科技", "岗位", "描述", "https://a.com/x"))
                .getApplyUrl()).isEqualTo("https://a.com/x");
        // 非法协议 → 40000，整体拒绝（不落库）
        assertThatThrownBy(() -> service.update(1L, 5L, request("示例科技", "岗位", "描述", "ftp://a.com/x")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    // ---------------------------------------------------------------- 求职进度（M21）

    @Test
    void create_defaultsProgressToPending() {
        JobDetail detail = service.create(1L, request("示例科技", "岗位", "描述"));

        assertThat(detail.getProgressStatus()).isEqualTo(Job.PROGRESS_PENDING);
    }

    @Test
    void list_exposesProgressStatus() {
        Job applied = job(1L, "岗位A", "描述A");
        applied.setProgressStatus(Job.PROGRESS_APPLIED);
        doReturn(pageOf(applied)).when(jobMapper).selectPage(any(), any());
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of());

        PageResult<JobView> result = service.list(1L, new PageQuery());

        assertThat(result.getRecords().get(0).getProgressStatus()).isEqualTo(Job.PROGRESS_APPLIED);
    }

    @Test
    void updateProgressStatus_anyTransitionAllowedAndUpdatedAtRefreshed() {
        Job existing = job(5L, "岗位", "描述");
        existing.setProgressStatus(Job.PROGRESS_PENDING);
        LocalDateTime before = LocalDateTime.now().minusDays(1).withNano(0);
        existing.setUpdatedAt(before);
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(null);

        // 任意状态互转（含直接跳到终态，不设状态机）
        JobDetail detail = service.updateProgressStatus(1L, 5L, Job.PROGRESS_OFFER);

        assertThat(detail.getProgressStatus()).isEqualTo(Job.PROGRESS_OFFER);
        assertThat(detail.getUpdatedAt()).isAfter(before);   // 内存装配的显式刷新值
        assertThat(detail.isAnalyzed()).isFalse();
    }

    @Test
    void updateProgressStatus_rejectsUnknownValue() {
        when(jobMapper.selectOne(any())).thenReturn(job(5L, "岗位", "描述"));

        assertThatThrownBy(() -> service.updateProgressStatus(1L, 5L, "WISHFUL_THINKING"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
        assertThatThrownBy(() -> service.updateProgressStatus(1L, 5L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
        // 小写不接受（枚举值大小写敏感，与 DTO 的 @Pattern 同一口径）
        assertThatThrownBy(() -> service.updateProgressStatus(1L, 5L, "offer"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    @Test
    void updateProgressStatus_missingOrForeign_throws404() {
        when(jobMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateProgressStatus(1L, 5L, Job.PROGRESS_APPLIED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40400));
    }

    @Test
    void update_fullUpdateKeepsProgressStatus() {
        Job existing = job(5L, "旧岗位名", "旧描述");
        existing.setProgressStatus(Job.PROGRESS_HR);
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(null);

        // 编辑弹窗只改描述类字段：重填表单不会把进度打回「待投递」
        JobDetail detail = service.update(1L, 5L, request("示例科技", "新岗位名", "新描述"));

        assertThat(detail.getProgressStatus()).isEqualTo(Job.PROGRESS_HR);
    }

    // ---------------------------------------------------------------- 测试数据

    private static Job job(Long id, String jobName, String description) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(1L);
        job.setCompanyName("示例科技");
        job.setJobName(jobName);
        job.setDescription(description);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private static JobAnalysis analysis(Long jobId, int versionNo, String sourceHash) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setJobId(jobId);
        analysis.setVersionNo(versionNo);
        analysis.setSourceHash(sourceHash);
        analysis.setStatus(JobAnalysis.STATUS_SUCCESS);
        return analysis;
    }

    private static JobSaveRequest request(String companyName, String jobName, String description) {
        return request(companyName, jobName, description, null);
    }

    private static JobSaveRequest request(String companyName, String jobName, String description, String applyUrl) {
        JobSaveRequest request = new JobSaveRequest();
        request.setCompanyName(companyName);
        request.setJobName(jobName);
        request.setDescription(description);
        request.setApplyUrl(applyUrl);
        return request;
    }

    private static Page<Job> pageOf(Job... jobs) {
        Page<Job> page = new Page<>(1, 10);
        page.setRecords(List.of(jobs));
        page.setTotal(jobs.length);
        return page;
    }

    /** 测试内的独立 hash 实现：不复用生产代码，保证"判定的依据"被独立验证 */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
