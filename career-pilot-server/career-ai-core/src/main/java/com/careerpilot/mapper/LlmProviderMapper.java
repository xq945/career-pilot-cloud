package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.LlmProvider;

/**
 * 模型服务配置 Mapper（空接口，查询一律走 Wrapper，项目惯例——无 XML、无自定义 SQL）。
 */
@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProvider> {
}
