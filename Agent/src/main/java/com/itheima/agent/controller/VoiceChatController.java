package com.itheima.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.agent.service.AudioSegmentService;
import com.itheima.agent.service.BaiduSpeechService;
import com.itheima.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 语音对话控制器：语音 → ASR 识别 → LLM 对话 → 分段 TTS → SSE 推送
 */
@RestController
@CrossOrigin("*")
public class VoiceChatController {

    private static final Logger log = LoggerFactory.getLogger(VoiceChatController.class);

    private final BaiduSpeechService speech;
    private final ChatService chatService;
    private final AudioSegmentService segmentService;
    private final ObjectMapper objectMapper;

    public VoiceChatController(BaiduSpeechService speech,
                               ChatService chatService,
                               AudioSegmentService segmentService) {
        this.speech = speech;
        this.chatService = chatService;
        this.segmentService = segmentService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 语音对话接口：接收音频，返回 SSE 流（文本 + 分段语音）
     */
    @PostMapping(value = "/api/voice-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter voiceChat(@RequestParam("audio") MultipartFile audio) {
        SseEmitter emitter = new SseEmitter(120000L);

        new Thread(() -> {
            try {
                // 1. 语音识别
                String asrJson = speech.recognize(audio.getBytes(), "wav");
                String userText = extractText(asrJson);

                if (userText.isEmpty()) {
                    // 识别为空
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("text", "");
                    segmentService.safeSend(emitter, "userText", msg);

                    segmentService.safeSend(emitter, "done", Map.of());
                    emitter.complete();
                    return;
                }

                // 2. 发送识别文字
                Map<String, Object> userMsg = new HashMap<>();
                userMsg.put("text", userText);
                segmentService.safeSend(emitter, "userText", userMsg);

                // 3. 进入对话循环
                runChatLoop(userText, emitter);

                // 4. 结束
                segmentService.safeSend(emitter, "done", Map.of());
                emitter.complete();

            } catch (Exception e) {
                log.error("语音对话异常: {}", e.getMessage(), e);
                Map<String, Object> err = new HashMap<>();
                err.put("message", "语音对话异常: " + e.getMessage());
                segmentService.safeSend(emitter, "error", err);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }).start();

        return emitter;
    }

    /**
     * 对话循环：流式获取 LLM 输出 → 分段 → TTS 合成 → SSE 推送
     */
    private void runChatLoop(String userText, SseEmitter emitter) {
        StringBuilder buffer = new StringBuilder();
        AtomicInteger segIndex = new AtomicInteger(0);

        try {
            chatService.chatStream(userText)
                    .doOnNext(chunk -> {
                        buffer.append(chunk);

                        // 循环切分：只要找到断点就发送
                        while (true) {
                            int bp = segmentService.findBreakPoint(buffer.toString());
                            if (bp == -1) break;

                            String segment = buffer.substring(0, bp);
                            buffer.delete(0, bp);

                            if (!segment.trim().isEmpty()) {
                                segmentService.sendAudioSegment(segment, segIndex.getAndIncrement(), emitter);
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        // 处理剩余内容
                        String remaining = buffer.toString().trim();
                        if (!remaining.isEmpty()) {
                            segmentService.sendAudioSegment(remaining, segIndex.getAndIncrement(), emitter);
                        }
                        if (segIndex.get() == 0) {
                            // 没有任何分段 → AI 返回为空
                            Map<String, Object> err = new HashMap<>();
                            err.put("message", "AI返回为空");
                            segmentService.safeSend(emitter, "error", err);
                        }
                    })
                    .doOnError(error -> log.error("对话流异常: {}", error.getMessage(), error))
                    .blockLast();

        } catch (Exception e) {
            log.error("runChatLoop 异常: {}", e.getMessage(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("message", "对话处理异常: " + e.getMessage());
            segmentService.safeSend(emitter, "error", err);
        }
    }

    /**
     * 从百度 ASR 返回的 JSON 中提取识别文本
     *
     * @param asrJson 百度 ASR 的原始 JSON 响应
     * @return 识别出的文字，失败时返回空字符串
     */
    @SuppressWarnings("unchecked")
    private String extractText(String asrJson) {
        try {
            Map<String, Object> json = objectMapper.readValue(asrJson, Map.class);
            Object errNo = json.get("err_no");
            int errCode = errNo instanceof Integer ? (Integer) errNo : 0;
            if (errCode != 0) {
                log.warn("ASR 识别出错，err_no={}", errCode);
                return "";
            }
            Object result = json.get("result");
            if (result instanceof List) {
                List<String> arr = (List<String>) result;
                if (!arr.isEmpty()) {
                    return arr.get(0);
                }
            }
            return "";
        } catch (Exception e) {
            log.error("解析 ASR 结果 JSON 失败: {}", e.getMessage());
            return "";
        }
    }
}
