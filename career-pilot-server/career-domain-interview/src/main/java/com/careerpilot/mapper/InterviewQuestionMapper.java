package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.InterviewQuestion;

/**
 * 面试题 Mapper（空接口，查询一律走 Wrapper，项目惯例——无 XML、无自定义 SQL）。
 *
 * {@code @Mapper} 是必需的：本项目未用 {@code @MapperScan}，Mapper 靠该注解逐个注册
 * （漏注解的表现是启动报「required a bean of type ...Mapper that could not be found」）。
 */
@Mapper
public interface InterviewQuestionMapper extends BaseMapper<InterviewQuestion> {
}
