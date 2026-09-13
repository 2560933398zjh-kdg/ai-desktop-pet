package com.itheima.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 语音分段服务：负责文本断句、清洗、TTS 合成与 SSE 推送
 */
@Service
public class AudioSegmentService {

    private static final Logger log = LoggerFactory.getLogger(AudioSegmentService.class);

    private static final int MIN_SEGMENT = 20;
    private static final int MAX_SEGMENT = 60;

    private final BaiduSpeechService speech;
    private final ObjectMapper mapper;

    public AudioSegmentService(BaiduSpeechService speech) {
        this.speech = speech;
        this.mapper = new ObjectMapper();
    }

    /**
     * 在文本中找到合适的断句点
     *
     * @param text 待断句的文本
     * @return 断点位置（不含），-1 表示无需切分
     */
    public int findBreakPoint(String text) {
        if (text == null || text.isEmpty()) return -1;
        int len = text.length();
        if (len < MIN_SEGMENT) return -1;

        // 跳过代码块内的文本
        int codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int codeEnd = text.indexOf("```", codeStart + 3);
            if (codeEnd >= 0 && codeStart < len) {
                // 简单跳过整段代码块
                String content;
                if (codeStart < len && codeEnd + 3 <= len) {
                    content = text.substring(0, codeStart) + text.substring(codeEnd + 3);
                    return findBreakPoint(content.trim());
                }
            }
        }

        // 区间上限
        int upper = Math.min(len, MAX_SEGMENT + 10);

        // 第一优先级：句号、感叹号、问号、分号、换行
        for (int i = MIN_SEGMENT; i < upper; i++) {
            char ch = text.charAt(i);
            if (ch == '\u3002' || ch == '\uff01' || ch == '\uff1f'
                    || ch == '\uff1b' || ch == '；' || ch == '；'
                    || ch == '\n') {
                return i + 1;
            }
            // 英文标点
            if (ch == '.' || ch == '!' || ch == '?' || ch == ';') {
                return i + 1;
            }
        }

        // 第二优先级：逗号、空格
        if (len >= MAX_SEGMENT) {
            int lower = MAX_SEGMENT - 10;
            int commaUpper = Math.min(len, MAX_SEGMENT + 10);
            for (int i = lower; i < commaUpper; i++) {
                char ch = text.charAt(i);
                if (ch == '\u3002' || ch == '\uff0c' || ch == '，'
                        || ch == ',' || ch == ' ') {
                    return i + 1;
                }
            }
            // 强制截断
            return MAX_SEGMENT - 1;
        }

        return -1;
    }

    /**
     * 清洗文本用于显示：去除 markdown 标记
     */
    public String cleanForDisplay(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = text;

        // 去掉代码块 ```...```
        result = result.replaceAll("```[\\s\\S]*?```", "");

        // 去掉行内代码 `...`
        result = result.replaceAll("`[^`]*`", "");

        // 去掉粗体 **...**
        result = result.replaceAll("\\*\\*[^*]*\\*\\*", "");

        // 去掉标题标记 #
        result = result.replaceAll("(?m)^#{1,6}\\s*", "");

        // 去掉列表标记
        result = result.replaceAll("(?m)^[-*]\\s+", "");

        // 去掉引用标记
        result = result.replaceAll("(?m)^>\\s*", "");

        // 去掉括号注释（中英文括号）
        result = result.replaceAll("\\([^)]*\\)", "");
        result = result.replaceAll("（[^）]*）", "");

        // 连续多个换行缩为一个
        result = result.replaceAll("\\n{3,}", "\n\n").replaceAll("\n", "\n");

        // 多个空格缩成一个
        result = result.replaceAll(" {2,}", " ");

        return result.trim();
    }

    /**
     * 清洗文本用于语音合成：比 cleanForDisplay 更激进
     */
    public String cleanForSpeech(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = text;

        // 去掉代码块
        result = result.replaceAll("```[\\s\\S]*?```", "");

        // 去掉行内代码
        result = result.replaceAll("`[^`]*`", "");

        // 去掉所有 markdown 标记
        result = result.replaceAll("\\*\\*", "");
        result = result.replaceAll("\\*", "");
        result = result.replaceAll("(?m)^#{1,6}\\s*", "");
        result = result.replaceAll("(?m)^[-*]\\s+", "");
        result = result.replaceAll("(?m)^>\\s*", "");
        result = result.replaceAll("_", "");

        // 去掉【】括号及内容
        result = result.replaceAll("【[^】]*】", "");

        // 去掉中英文括号
        result = result.replaceAll("\\([^)]*\\)", "");
        result = result.replaceAll("（[^）]*）", "");

        // 多个空格缩成一个
        result = result.replaceAll(" {2,}", " ");

        return result.trim();
    }

    /**
     * 发送单段语音：清洗 → TTS 合成 → SSE 推送
     */
    public void sendAudioSegment(String originalText, int index, SseEmitter emitter) {
        String dispText = cleanForDisplay(originalText);
        String cleanText = cleanForSpeech(originalText);

        if (cleanText.isEmpty()) {
            // 兜底：无有效语音文本，发送仅包含显示文本的事件
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("data", "");
            fallback.put("text", dispText);
            fallback.put("index", index);
            safeSend(emitter, "audio", fallback);
            return;
        }

        try {
            byte[] audioBytes = speech.synthesize(cleanText);
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("data", base64Audio);
            eventData.put("text", dispText);
            eventData.put("index", index);

            safeSend(emitter, "audio", eventData);
        } catch (IOException e) {
            log.warn("TTS 合成失败，发送兜底事件: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("data", "");
            fallback.put("text", dispText);
            fallback.put("index", index);
            safeSend(emitter, "audio", fallback);
        }
    }

    /**
     * 安全的 SSE 发送：忽略客户端断开导致的异常
     */
    public void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (IllegalStateException | IOException e) {
            log.debug("SSE 发送跳过（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
