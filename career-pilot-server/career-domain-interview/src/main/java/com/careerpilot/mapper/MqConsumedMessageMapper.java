package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.MqConsumedMessage;

/**
 * MQ 消费幂等表 Mapper（阶段 8）。
 */
@Mapper
public interface MqConsumedMessageMapper extends BaseMapper<MqConsumedMessage> {
}
