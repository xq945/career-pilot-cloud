package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.AgentChatMessage;

/**
 * 智能体对话消息 Mapper（空接口，查询一律走 Wrapper，项目惯例——无 XML、无自定义 SQL）。
 */
@Mapper
public interface AgentChatMessageMapper extends BaseMapper<AgentChatMessage> {
}
