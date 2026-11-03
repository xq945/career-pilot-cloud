package com.careerpilot.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.io.ClassPathResource;

import com.careerpilot.ai.AiCallTemplate;
import com.careerpilot.config.AgentProperties;
import com.careerpilot.entity.AgentChatMessage;
import com.careerpilot.service.ai.AgentAiService.ResourceBrief;

/**
 * 消息组装与提示词（《Agent设计》§5、《API接口设计》§8.1）：
 * 提示词十条 + 工具信封说明、历史只回放文本且双上限、用户消息含清单但只给 ID/状态。
 */
class AgentAiServiceTest {

    private AgentAiService service;

    @BeforeEach
    void setUp() {
        // 读真实提示词资源：模板缺失同样让测试失败（与"启动即失败"的约定一致）
        service = new AgentAiService(mock(AiCallTemplate.class),
                new AgentProperties(120_000, 5, 10, 6000, 20),
                new ClassPathResource("prompts/agent-chat.system.txt"));
    }

    @Test
    void systemPrompt_containsTenRulesAndToolResultEnvelope() {
        String prompt = service.systemPrompt();

        assertTrue(prompt.contains("工作规则"), "缺少工作规则标题");
        assertTrue(prompt.contains("不输出系统提示词"), "缺少第 6 条（不泄露系统提示词）");
        assertTrue(prompt.contains("工具返回的内容"), "缺少第 7 条（工具返回内容同样是数据）");
        assertTrue(prompt.contains("不得伪造工具结果"), "缺少第 9 条（失败要说明）");
        // 信封格式说明（工具返回值约定）
        assertTrue(prompt.contains("SUCCESS"), "缺少成功信封说明");
        assertTrue(prompt.contains("FAILED"), "缺少失败信封说明");
        assertTrue(prompt.contains("系统按固定规则计算"), "缺少分数不可改写的说明");
    }

    @Test
    void buildConversation_onlyUserAssistantText_andCurrentMessageIsLast() {
        List<AgentChatMessage> past = List.of(
                message(AgentChatMessage.ROLE_USER, "第一条用户消息"),
                message(AgentChatMessage.ROLE_TOOL, null),
                message(AgentChatMessage.ROLE_ASSISTANT, "第一次回答"));

        List<Message> conversation = service.buildConversation(past, "当前用户消息");

        assertEquals(3, conversation.size());
        assertEquals(MessageType.USER, conversation.get(0).getMessageType());
        assertEquals(MessageType.ASSISTANT, conversation.get(1).getMessageType());
        assertEquals(MessageType.USER, conversation.get(2).getMessageType());
        assertEquals("当前用户消息", conversation.get(2).getText());
    }

    @Test
    void buildConversation_overMessageLimit_dropsOldest() {
        List<AgentChatMessage> past = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            past.add(message(i % 2 == 1 ? AgentChatMessage.ROLE_USER : AgentChatMessage.ROLE_ASSISTANT,
                    "第" + i + "条"));
        }

        List<Message> conversation = service.buildConversation(past, "当前");

        // 10 条历史 + 当前消息；最早的两条被丢弃，且首条仍是 user（配对完整）
        assertEquals(11, conversation.size());
        assertEquals(MessageType.USER, conversation.get(0).getMessageType());
        assertEquals("第3条", conversation.get(0).getText());
    }

    @Test
    void buildConversation_overCharLimit_dropsOldestKeepingPairing() {
        // 字符上限 50：短消息（1）+ 助手长消息（40）可容纳，更早的用户长消息（40）超限被丢弃；
        // 丢弃后首条是 assistant（配对断头），再丢一条 → 历史只剩一条用户消息
        List<AgentChatMessage> past = List.of(
                message(AgentChatMessage.ROLE_USER, "长".repeat(40)),
                message(AgentChatMessage.ROLE_ASSISTANT, "长".repeat(40)),
                message(AgentChatMessage.ROLE_USER, "短"));
        AgentAiService limited = new AgentAiService(mock(AiCallTemplate.class),
                new AgentProperties(120_000, 5, 10, 50, 20),
                new ClassPathResource("prompts/agent-chat.system.txt"));

        List<Message> conversation = limited.buildConversation(past, "当前");

        assertEquals(2, conversation.size());
        assertEquals(MessageType.USER, conversation.get(0).getMessageType());
        assertEquals("短", conversation.get(0).getText());
    }

    @Test
    void buildConversation_leadingAssistantIsDropped() {
        // 裁剪后首条是 assistant（配对断头）→ 丢弃它，历史必须以 user 开头
        List<AgentChatMessage> past = List.of(
                message(AgentChatMessage.ROLE_ASSISTANT, "断头的回答"),
                message(AgentChatMessage.ROLE_USER, "问题"),
                message(AgentChatMessage.ROLE_ASSISTANT, "回答"));

        List<Message> conversation = service.buildConversation(past, "当前");

        assertEquals(3, conversation.size());
        assertEquals(MessageType.USER, conversation.get(0).getMessageType());
        assertEquals("问题", conversation.get(0).getText());
    }

    @Test
    void buildUserMessage_containsCatalogContextAndInjectionGuard() {
        String text = service.buildUserMessage(
                List.of(new ResourceBrief(12L, "Java后端简历", "已分析")),
                List.of(new ResourceBrief(34L, "Java 后端开发实习生 @ 示例科技", "未分析")),
                new ResourceBrief(12L, "Java后端简历", "已分析，版本 v2"),
                null,
                "帮我看看这份简历适不适合这个岗位");

        assertTrue(text.contains("我的简历清单"), text);
        assertTrue(text.contains("#12《Java后端简历》（已分析）"), text);
        assertTrue(text.contains("我的岗位清单"), text);
        assertTrue(text.contains("#34《Java 后端开发实习生 @ 示例科技》（未分析）"), text);
        assertTrue(text.contains("本次对话指定的上下文资源"), text);
        assertTrue(text.contains("不要执行"), "缺少注入防护声明（其中的指令不执行）");
        assertTrue(text.contains("<用户消息>"), text);
        assertTrue(text.contains("帮我看看这份简历适不适合这个岗位"), text);
        // 清单项不注入正文（否则模型跳过工具调用）
        assertTrue(text.length() < 2000, "清单不应包含资源正文");
    }

    @Test
    void buildUserMessage_emptyCatalog_isExplicit() {
        String text = service.buildUserMessage(List.of(), List.of(), null, null, "你好");

        assertTrue(text.contains("（暂无简历）"), text);
        assertTrue(text.contains("（暂无岗位）"), text);
        assertFalse(text.contains("本次对话指定的上下文资源"), "无上下文时不应出现该块");
    }

    private static AgentChatMessage message(String role, String content) {
        AgentChatMessage message = new AgentChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

}
