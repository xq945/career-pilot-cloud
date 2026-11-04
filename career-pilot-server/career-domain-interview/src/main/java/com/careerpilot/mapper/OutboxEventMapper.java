package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.OutboxEvent;

/**
 * 事件投递本地消息表 Mapper（阶段 8）。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
