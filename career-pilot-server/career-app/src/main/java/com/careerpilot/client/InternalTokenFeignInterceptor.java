package com.careerpilot.client;

import org.springframework.stereotype.Component;

import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.security.InternalTokenService;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用的身份传播（阶段 5）：从当前 SecurityContext 取用户，
 * 按目标服务签发内部令牌（aud=目标服务名），下游只接受自己的 aud。
 *
 * <p>异步优化任务在执行前已建立 SecurityContext（阶段 1 · B1），因此同样可用。
 */
@Component
public class InternalTokenFeignInterceptor implements RequestInterceptor {

    private final InternalTokenService internalTokenService;

    public InternalTokenFeignInterceptor(InternalTokenService internalTokenService) {
        this.internalTokenService = internalTokenService;
    }

    @Override
    public void apply(RequestTemplate template) {
        AuthenticatedUser user = CurrentUser.require();
        String target = template.feignTarget() == null ? "" : template.feignTarget().name();
        String audience = target.startsWith("career-") ? target : "career-pilot-server";
        template.header(InternalTokenService.HEADER,
                internalTokenService.issue(user.id(), user.username(), audience));
    }
}
