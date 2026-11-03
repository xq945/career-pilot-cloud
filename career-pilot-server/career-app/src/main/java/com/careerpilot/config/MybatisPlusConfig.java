package com.careerpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

/**
 * MyBatis-Plus 配置（M3 引入）。
 *
 * 注册分页插件：不注册时 {@code Page} 查询**不会真正分页**（会全量返回），
 * 是 MyBatis-Plus 的常见坑。所有列表接口（《API接口设计》§10.1）都依赖它。
 *
 * 显式指定 DbType.MYSQL，避免插件按方言自动探测（多数据源场景下会误判）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}
