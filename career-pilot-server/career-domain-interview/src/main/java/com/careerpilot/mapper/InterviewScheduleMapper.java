package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.InterviewSchedule;

/**
 * 面试日程 Mapper（空接口，查询一律走 Wrapper，项目惯例）。
 */
@Mapper
public interface InterviewScheduleMapper extends BaseMapper<InterviewSchedule> {
}
