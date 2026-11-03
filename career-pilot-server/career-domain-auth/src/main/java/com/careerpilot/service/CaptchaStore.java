package com.careerpilot.service;

/**
 * 验证码存储抽象（阶段 4）：本地 Map 与 Redis 两种实现。
 *
 * <p>多实例部署必须使用 Redis，否则实例 A 生成的验证码在实例 B 校验必然失败。
 */
interface CaptchaStore {

    void save(String captchaId, String code, int ttlSeconds);

    /** 原子取出并删除；不存在或已过期返回 null */
    String take(String captchaId);

    void remove(String captchaId);

    void evictExpired();

    int size();
}
