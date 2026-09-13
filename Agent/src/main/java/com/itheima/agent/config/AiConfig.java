package com.itheima.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI OpenAI 配置类
 * 手动配置 OpenAI API 客户端，不依赖自动配置
 */
@Configuration
public class AiConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    /**
     * 创建 OpenAiApi 客户端实例（是 Spring AI 提供的低层 HTTP 客户端，负责与 OpenAI REST API 交互）
     *
     * @return OpenAiApi 实例，配置了 baseUrl 和 apiKey
     */
    @Bean
    public OpenAiApi openAiApi() {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    /**
     * 创建 OpenAiChatModel 实例（是 Spring AI 中针对 OpenAI 聊天补全接口的模型实现，实现了 ChatModel 接口）
     *
     * @param openAiApi OpenAiApi 客户端实例
     * @return OpenAiChatModel 实例，配置了默认模型选项
     */
    @Bean
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /**
     * 创建 ChatClient.Builder 实例（是 Spring AI 提供的高级 流式/同步 客户端 API，封装了对话、工具调用、记忆等能力）
     *
     * @param chatModel ChatModel 实例
     * @return ChatClient.Builder 实例，用于构建 ChatClient
     */
    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
