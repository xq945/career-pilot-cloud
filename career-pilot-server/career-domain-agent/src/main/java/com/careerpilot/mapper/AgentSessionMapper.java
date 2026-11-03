package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.AgentSession;

/**
 * 智能体会话 Mapper（空接口，查询一律走 Wrapper，项目惯例——无 XML、无自定义 SQL）。
 *
 * {@code @Mapper} 是必需的：本项目未用 {@code @MapperScan}，Mapper 靠该注解逐个注册。
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
