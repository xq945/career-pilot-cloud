package com.careerpilot.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.ResumeOptimization;

/**
 * 简历定向优化记录 Mapper（对应《数据库设计》第 11 张表）。
 * 除 {@link #currentTimestamp()} 外，其余查询由 Service 以 LambdaQuery 构造。
 */
@Mapper
public interface ResumeOptimizationMapper extends BaseMapper<ResumeOptimization> {

    /**
     * 数据库当前时间：恢复调度统一以 DB 时钟判定超时，避免多实例时区/时钟漂移
     * 把仍存活的任务误判为死亡（阶段 1 · B3）。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime currentTimestamp();
}
