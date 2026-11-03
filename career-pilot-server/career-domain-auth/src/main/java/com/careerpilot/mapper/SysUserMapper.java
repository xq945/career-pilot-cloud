package com.careerpilot.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpilot.entity.SysUser;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
