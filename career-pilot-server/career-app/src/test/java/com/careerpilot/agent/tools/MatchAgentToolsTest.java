package com.careerpilot.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.careerpilot.dto.MatchDetail;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.MatchService;

/**
 * 匹配工具（《Agent设计》§4.4）与 REST 的差异契约：工具只发一次
 * {@code createForAgent} 远程调用，**缺分析自动补齐由匹配服务完成**；
 * 分数只来自规则计算（AG-08）。
 */
class MatchAgentToolsTest {

    private MatchService matchService;

    private MatchAgentTools tools;

    @BeforeEach
    void setUp() {
        matchService = mock(MatchService.class);
        tools = new MatchAgentTools(matchService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "tester"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchResumeToJob_delegatesToAgentEntryOnce() {
        when(matchService.createForAgent(7L, 12L, 34L)).thenReturn(matchDetail(82.4));

        String payload = tools.matchResumeToJob(12L, 34L);

        verify(matchService).createForAgent(7L, 12L, 34L);
        assertTrue(payload.contains("\"status\":\"SUCCESS\""), payload);
        assertTrue(payload.contains("82.4"), payload);
        assertTrue(payload.contains("Java 后端简历"), payload);
    }

    private static MatchDetail matchDetail(double score) {
        MatchDetail detail = new MatchDetail();
        detail.setId(701L);
        detail.setResumeId(12L);
        detail.setResumeName("Java 后端简历");
        detail.setJobId(34L);
        detail.setJobName("Java 后端开发实习生");
        detail.setMatchScore(score);
        return detail;
    }

}
