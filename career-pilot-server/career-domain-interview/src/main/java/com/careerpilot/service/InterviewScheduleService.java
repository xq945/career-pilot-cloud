package com.careerpilot.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.InterviewScheduleSaveRequest;
import com.careerpilot.dto.InterviewScheduleView;
import com.careerpilot.dto.ScheduleParseResponse;
import com.careerpilot.dto.ScheduleParseResult;
import com.careerpilot.dto.ai.ScheduleParseAiOutput;
import com.careerpilot.entity.InterviewSchedule;
import com.careerpilot.mapper.InterviewScheduleMapper;
import com.careerpilot.service.schedule.InterviewInvitationParser;
import com.careerpilot.service.schedule.ScheduleParseAiFallback;

/**
 * 面试日程业务（M17，《API接口设计》§9A）：解析（规则优先 + AI 兜底）、CRUD、状态流转。
 *
 * <p>三条与文档一一对应的约定：
 * <ul>
 *   <li>**解析两段式不落库**：{@link #parse} 只返回预览，用户确认后走 {@link #create}；</li>
 *   <li>**时间必须为未来**：创建与更新均校验（前端 `disabledDate` 只是体验层预拦截）；</li>
 *   <li>**状态机**：仅 `PENDING` 可编辑 / 可流转；终态不可回退；`EXPIRED` 仅系统写入
 *       （DTO 白名单已挡，此层再兜一次）。删除为**物理删除**（《数据库设计》5.16）。</li>
 * </ul>
 */
@Service
public class InterviewScheduleService {

    private static final Logger log = LoggerFactory.getLogger(InterviewScheduleService.class);

    /** 合法状态全集（列表筛选与写入白名单共用） */
    private static final Set<String> ALL_STATUSES = Set.of(
            InterviewSchedule.STATUS_PENDING, InterviewSchedule.STATUS_COMPLETED,
            InterviewSchedule.STATUS_CANCELLED, InterviewSchedule.STATUS_EXPIRED);

    private final InterviewScheduleMapper scheduleMapper;

    private final InterviewInvitationParser invitationParser;

    private final ScheduleParseAiFallback aiFallback;

    public InterviewScheduleService(InterviewScheduleMapper scheduleMapper,
                                    InterviewInvitationParser invitationParser,
                                    ScheduleParseAiFallback aiFallback) {
        this.scheduleMapper = scheduleMapper;
        this.invitationParser = invitationParser;
        this.aiFallback = aiFallback;
    }

    // ---------------------------------------------------------------- 解析（§9A.1）

    /**
     * 解析面试邀请：**规则优先（0.95）→ AI 兜底（0.80）→ 失败态**。
     * 全程不落库；失败返回 `success = false` 的可读消息（HTTP 200），引导手工填写。
     */
    public ScheduleParseResponse parse(String rawText) {
        Clock clock = Clock.systemDefaultZone();

        InterviewInvitationParser.ParseOutcome ruleOutcome = invitationParser.parse(rawText, clock);
        if (ruleOutcome != null) {
            return ScheduleParseResponse.success(InterviewSchedule.SOURCE_RULE,
                    BigDecimal.valueOf(InterviewInvitationParser.RULE_CONFIDENCE),
                    toParseResult(ruleOutcome));
        }

        ScheduleParseAiOutput aiOutput = aiFallback.parse(rawText, clock);
        if (aiOutput != null) {
            ScheduleParseResult result = toParseResult(aiOutput);
            if (hasAnyField(result)) {
                return ScheduleParseResponse.success(InterviewSchedule.SOURCE_AI,
                        BigDecimal.valueOf(ScheduleParseAiFallback.AI_CONFIDENCE), result);
            }
        }
        return ScheduleParseResponse.failure("未能识别邀请信息，请手工填写");
    }

    // ---------------------------------------------------------------- 创建 / 更新（§9A.2 / §9A.5）

    public InterviewScheduleView create(Long userId, InterviewScheduleSaveRequest request) {
        requireFuture(request.getInterviewTime());

        InterviewSchedule entity = new InterviewSchedule();
        entity.setUserId(userId);
        applyRequest(entity, request);
        entity.setStatus(InterviewSchedule.STATUS_PENDING);
        entity.setParseSource(normalizeSource(request.getParseSource()));
        entity.setParseConfidence(request.getConfidence());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        scheduleMapper.insert(entity);

        log.info("面试日程已创建：id={} company={} time={} source={}",
                entity.getId(), entity.getCompanyName(), entity.getInterviewTime(), entity.getParseSource());
        return toView(entity);
    }

    /** 全量更新；仅 PENDING 可编辑（终态日程请重新创建）。**编辑不改变初始解析来源**。 */
    public InterviewScheduleView update(Long userId, Long id, InterviewScheduleSaveRequest request) {
        InterviewSchedule record = getOwned(userId, id);
        if (!record.pending()) {
            throw BusinessException.badRequest("已结束的日程不可修改");
        }
        requireFuture(request.getInterviewTime());
        applyRequest(record, request);
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        scheduleMapper.updateById(record);
        return toView(record);
    }

    public InterviewScheduleView updateStatus(Long userId, Long id, String targetStatus) {
        InterviewSchedule record = getOwned(userId, id);
        if (!record.pending()) {
            throw BusinessException.badRequest("该日程已结束，不能再变更状态");
        }
        // DTO 的 @Pattern 已拒绝 EXPIRED；此处为服务层兜底（防绕过校验的调用路径）
        if (!InterviewSchedule.STATUS_COMPLETED.equals(targetStatus)
                && !InterviewSchedule.STATUS_CANCELLED.equals(targetStatus)) {
            throw BusinessException.badRequest("目标状态不合法");
        }
        record.setStatus(targetStatus);
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        scheduleMapper.updateById(record);
        log.info("面试日程状态流转：id={} -> {}", id, targetStatus);
        return toView(record);
    }

    /** 物理删除（《数据库设计》5.16 说明了与其余表逻辑删除的差异） */
    public void delete(Long userId, Long id) {
        InterviewSchedule record = getOwned(userId, id);
        scheduleMapper.deleteById(id);
        log.info("面试日程已删除（物理）：id={} company={}", id, record.getCompanyName());
    }

    // ---------------------------------------------------------------- 查询（§9A.3 / §9A.4）

    public PageResult<InterviewScheduleView> list(Long userId, String status, LocalDate from, LocalDate to,
                                                  PageQuery query) {
        LambdaQueryWrapper<InterviewSchedule> wrapper = Wrappers.<InterviewSchedule>lambdaQuery()
                .eq(InterviewSchedule::getUserId, userId)
                // 默认按面试时间升序（未来在前）；id 倒序作稳定 tie-breaker
                .orderByAsc(InterviewSchedule::getInterviewTime)
                .orderByDesc(InterviewSchedule::getId);
        if (status != null && !status.isBlank()) {
            if (!ALL_STATUSES.contains(status)) {
                throw BusinessException.badRequest("状态筛选值不合法");
            }
            wrapper.eq(InterviewSchedule::getStatus, status);
        }
        if (from != null) {
            wrapper.ge(InterviewSchedule::getInterviewTime, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt(InterviewSchedule::getInterviewTime, to.plusDays(1).atStartOfDay());
        }

        Page<InterviewSchedule> page = new Page<>(query.getPage(), query.getSize());
        IPage<InterviewSchedule> result = scheduleMapper.selectPage(page, wrapper);
        return PageResult.of(result, result.getRecords().stream().map(InterviewScheduleService::toView).toList());
    }

    public InterviewScheduleView detail(Long userId, Long id) {
        return toView(getOwned(userId, id));
    }

    // ---------------------------------------------------------------- 内部

    /** 归属校验：查不到（不存在或属于他人）一律 404，不区分二者 */
    private InterviewSchedule getOwned(Long userId, Long id) {
        InterviewSchedule record = scheduleMapper.selectOne(Wrappers.<InterviewSchedule>lambdaQuery()
                .eq(InterviewSchedule::getId, id)
                .eq(InterviewSchedule::getUserId, userId));
        if (record == null) {
            throw BusinessException.notFound("面试日程不存在");
        }
        return record;
    }

    private static void applyRequest(InterviewSchedule entity, InterviewScheduleSaveRequest request) {
        entity.setCompanyName(request.getCompanyName().strip());
        entity.setPosition(request.getPosition().strip());
        entity.setInterviewTime(request.getInterviewTime().withNano(0));
        entity.setInterviewType(request.getInterviewType() == null
                ? InterviewSchedule.TYPE_VIDEO : request.getInterviewType());
        entity.setMeetingLink(blankToNull(request.getMeetingLink()));
        entity.setRoundNumber(request.getRoundNumber() == null ? 1 : request.getRoundNumber());
        entity.setInterviewer(blankToNull(request.getInterviewer()));
        entity.setNotes(blankToNull(request.getNotes()));
    }

    private static void requireFuture(LocalDateTime interviewTime) {
        if (interviewTime == null || interviewTime.isBefore(LocalDateTime.now())) {
            throw BusinessException.badRequest("面试时间必须是未来的时间");
        }
    }

    /** 解析来源归一：缺省 MANUAL；非法值同样回落 MANUAL（不因来源标识拒单） */
    private static String normalizeSource(String source) {
        if (InterviewSchedule.SOURCE_RULE.equals(source) || InterviewSchedule.SOURCE_AI.equals(source)) {
            return source;
        }
        return InterviewSchedule.SOURCE_MANUAL;
    }

    private static ScheduleParseResult toParseResult(InterviewInvitationParser.ParseOutcome outcome) {
        ScheduleParseResult result = new ScheduleParseResult();
        result.setCompanyName(outcome.companyName());
        result.setPosition(outcome.position());
        result.setInterviewTime(outcome.interviewTime());
        result.setInterviewType(outcome.interviewType());
        result.setMeetingLink(outcome.meetingLink());
        result.setRoundNumber(outcome.roundNumber());
        result.setInterviewer(outcome.interviewer());
        result.setNotes(outcome.notes());
        return result;
    }

    private static ScheduleParseResult toParseResult(ScheduleParseAiOutput output) {
        ScheduleParseResult result = new ScheduleParseResult();
        result.setCompanyName(output.getCompanyName());
        result.setPosition(output.getPosition());
        result.setInterviewTime(parseIsoTime(output.getInterviewTime()));
        // 类型未识别时给表单默认值（用户可改）；其余字段原样回填
        result.setInterviewType(output.getInterviewType() == null
                ? InterviewSchedule.TYPE_VIDEO : output.getInterviewType());
        result.setMeetingLink(output.getMeetingLink());
        result.setRoundNumber(output.getRoundNumber());
        result.setInterviewer(output.getInterviewer());
        result.setNotes(output.getNotes());
        return result;
    }

    private static LocalDateTime parseIsoTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.strip());
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** AI 返回全空（没有任何可用字段）视为兜底失败 */
    private static boolean hasAnyField(ScheduleParseResult result) {
        return result.getCompanyName() != null || result.getPosition() != null
                || result.getInterviewTime() != null || result.getMeetingLink() != null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static InterviewScheduleView toView(InterviewSchedule record) {
        InterviewScheduleView view = new InterviewScheduleView();
        view.setId(record.getId());
        view.setCompanyName(record.getCompanyName());
        view.setPosition(record.getPosition());
        view.setInterviewTime(record.getInterviewTime());
        view.setInterviewType(record.getInterviewType());
        view.setMeetingLink(record.getMeetingLink());
        view.setRoundNumber(record.getRoundNumber());
        view.setInterviewer(record.getInterviewer());
        view.setNotes(record.getNotes());
        view.setStatus(record.getStatus());
        view.setParseSource(record.getParseSource());
        view.setParseConfidence(record.getParseConfidence());
        view.setCreatedAt(record.getCreatedAt());
        view.setUpdatedAt(record.getUpdatedAt());
        return view;
    }

}
