package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.MatchRecord;

/**
 * 匹配记录 Mapper：仅依赖 MyBatis-Plus 的 BaseMapper，无自定义 SQL
 * （列表/详情的内联名称与版本号由 Service 层分批查询后内存 join，沿用 M4/M5 范式）。
 */
@Mapper
public interface MatchRecordMapper extends BaseMapper<MatchRecord> {
}
