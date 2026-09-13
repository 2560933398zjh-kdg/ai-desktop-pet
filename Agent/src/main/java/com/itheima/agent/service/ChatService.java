package com.itheima.agent.service;

import com.itheima.agent.tool.FeishuTool;
import com.itheima.agent.tool.FileTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话服务
 * <p>
 * 基于 Spring AI {@link ChatClient} 提供流式对话能力，
 * 构造时注入预设的系统指令，注册文件与飞书工具，并维护对话记忆。
 * </p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    /**
     * 构造注入 ChatClient.Builder、系统提示词、工具与记忆
     *
     * @param builder      ChatClient.Builder Bean（由 {@code AiConfig} 提供）
     * @param systemPrompt 系统级提示词，从 {@code bailian.system-prompt} 配置项注入
     * @param fileTools    文件操作工具
     * @param feishuTool   飞书机器人工具
     */
    public ChatService(ChatClient.Builder builder,
                       @Value("${bailian.system-prompt}") String systemPrompt,
                       FileTools fileTools,
                       FeishuTool feishuTool) {
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .build();
        this.chatClient = builder
                .defaultSystem(systemPrompt)
                .defaultTools(fileTools, feishuTool)
                .build();
    }

    /**
     * 流式对话
     * <p>
     * 发送用户消息并返回 SSE 流式响应，每条内容到达时立即推送。
     * 使用 {@link MessageChatMemoryAdvisor} 维护多轮对话上下文。
     * 发生错误时记录日志并终止流。
     * </p>
     *
     * @param userMessage 用户输入的消息文本
     * @return 流式返回模型响应的 {@link Flux}{@code <String>}
     */
    public Flux<String> chatStream(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .stream()
                .content()
                .doOnError(error -> log.error("对话流式响应异常: {}", error.getMessage(), error));
    }
}
