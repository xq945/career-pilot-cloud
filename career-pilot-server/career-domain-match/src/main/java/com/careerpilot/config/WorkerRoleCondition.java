package com.careerpilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Worker 角色开关（阶段 7）：{@code app.optimization.role=api} 时**不装配** Stream 消费者
 * 与恢复调度，其余取值（all / worker / 未配置）均装配；{@code app.match.mode=remote}
 * （匹配域已拆出，单体侧）同样不装配，避免单体误消费任务。
 *
 * <p>这样可以把"接收请求的实例"与"执行任务的实例"分开扩容：
 * API 实例设 role=api，Worker 实例设 role=worker；两者共用同一套本地服务与生产者。
 */
public class WorkerRoleCondition extends SpringBootCondition {

    private static final String ROLE_PROPERTY = "app.optimization.role";

    private static final String MATCH_MODE_PROPERTY = "app.match.mode";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty(ROLE_PROPERTY, "all");
        String matchMode = context.getEnvironment().getProperty(MATCH_MODE_PROPERTY, "local");
        boolean matches = !"api".equalsIgnoreCase(role) && !"remote".equalsIgnoreCase(matchMode);
        return new ConditionOutcome(matches,
                ROLE_PROPERTY + "=" + role + ", " + MATCH_MODE_PROPERTY + "=" + matchMode);
    }
}
