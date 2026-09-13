package com.itheima.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书机器人工具，供大模型调用
 */
@Component
public class FeishuTool {

    @Value("${feishu.webhook-url}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Tool(name = "sendFeishuMessage", description = "发送文本消息到飞书群聊机器人")
    public String sendFeishuMessage(@ToolParam(description = "要发送的消息内容") String content) {
        Map<String, Object> textMap = new HashMap<>();
        textMap.put("text", content);

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("msg_type", "text");
        bodyMap.put("content", textMap);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(bodyMap, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            return "飞书消息发送成功，HTTP状态: " + response.getStatusCode();
        } catch (Exception e) {
            return "飞书消息发送失败: " + e.getMessage();
        }
    }
}
