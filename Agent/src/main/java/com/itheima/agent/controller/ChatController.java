package com.itheima.agent.controller;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itheima.agent.service.ChatService;

import reactor.core.publisher.Flux;

/**
 * 对话接口控制器
 * <p>
 * 提供基于 SSE 的流式文本对话 REST 接口，
 * 接收用户消息并返回模型流式响应。
 * </p>
 */
@RestController  //标记为 REST 控制器，返回 JSON 或文本流
@RequestMapping("/api")   //所有接口前缀为 /api
@CrossOrigin("*")   //允许跨域，方便前后端分离开发
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    /**
     * 构造注入 {@link ChatService}
     *
     * @param chatService 对话服务 Bean
     */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 流式文本对话
     * <p>
     * 接收 JSON 请求体中的 {@code text} 字段作为用户消息，
     * 返回 SSE 格式的流式响应。若消息为空白则直接返回 {@code [DONE]}。
     * </p>
     *
     * @param request 请求体 Map，需包含 {@code text} 键
     * @return SSE 流式 Flux，每个窗口聚合后输出，流结束追加 {@code [DONE]}
     */
    @PostMapping(value = "/text-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> textChat(@RequestBody Map<String, Object> request) {
        // 1. 提取文本
        // 2. 校验空白
        // 3. 调用 service 并处理流
        Object textObj = request.get("text");
        String text = textObj != null ? textObj.toString() : null;

        if (text == null || text.isBlank()) {
            return Flux.just("[DONE]");
        }

        return chatService.chatStream(text)
                .window(Duration.ofMillis(100))
                .flatMap(w -> w.reduce("", String::concat))
                .filter(chunk -> !chunk.isEmpty())
                .concatWithValues("[DONE]")
                .doOnError(error -> log.error("SSE 流式输出异常: {}", error.getMessage(), error));
    }
}
