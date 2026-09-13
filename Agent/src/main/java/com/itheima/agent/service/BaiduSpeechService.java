package com.itheima.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 百度语音服务（ASR 语音识别 + TTS 语音合成）
 */
@Service
public class BaiduSpeechService {

    private static final Logger log = LoggerFactory.getLogger(BaiduSpeechService.class);

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${baidu.api-key}")
    private String apiKey;

    @Value("${baidu.secret-key}")
    private String secretKey;

    @Value("${baidu.asr-url}")
    private String asrUrl;

    @Value("${baidu.tts-url}")
    private String ttsUrl;

    @Value("${baidu.tts-voice-id}")
    private int ttsVoiceId;

    public BaiduSpeechService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取百度 API 的 access_token（OAuth 2.0 Client Credentials 模式）
     */
    private String getAccessToken() throws IOException {
        String url = TOKEN_URL + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("获取百度 access_token 失败，HTTP 状态码: {}", response.code());
                throw new IOException("获取 access_token 失败: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            String accessToken = (String) json.get("access_token");
            if (accessToken == null) {
                log.error("百度 token 响应中缺少 access_token: {}", body);
                throw new IOException("access_token 为空");
            }
            log.info("百度 access_token 获取成功");
            return accessToken;
        }
    }

    /**
     * 语音识别（ASR）：将音频 byte[] 发送给百度 API，返回识别结果 JSON
     *
     * @param audio  音频数据
     * @param format 音频格式（如 "pcm", "wav"）
     * @return 百度 ASR 的完整 JSON 响应字符串
     */
    public String recognize(byte[] audio, String format) throws IOException {
        String token = getAccessToken();
        String speech = Base64.getEncoder().encodeToString(audio);

        Map<String, Object> params = new HashMap<>();
        params.put("format", format);
        params.put("rate", 16000);
        params.put("channel", 1);
        params.put("dev_pid", 1537);
        params.put("token", token);
        params.put("cuid", "cyberpet");
        params.put("len", audio.length);
        params.put("speech", speech);

        String json = objectMapper.writeValueAsString(params);
        log.info("ASR 请求体长度: {}", json.length());

        Request request = new Request.Builder()
                .url(asrUrl)
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("ASR 请求失败，HTTP 状态码: {}", response.code());
                throw new IOException("ASR 请求失败: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            log.info("ASR 识别结果: {}", body);
            return body;
        }
    }

    /**
     * 语音合成（TTS）：将文本发送给百度 API，返回 MP3 音频字节数组
     *
     * @param text 要合成的文本
     * @return MP3 音频的字节数组
     */
    public byte[] synthesize(String text) throws IOException {
        String token = getAccessToken();

        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("voice_id", ttsVoiceId);
        params.put("media_type", "mp3");

        String json = objectMapper.writeValueAsString(params);
        log.info("TTS 请求体长度: {}", json.length());

        // token 作为查询参数拼接在 URL 上，不在 Body 中
        String url = ttsUrl + "?access_token=" + token;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("TTS 请求失败，HTTP 状态码: {}", response.code());
                throw new IOException("TTS 请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            if (contentType != null && contentType.startsWith("audio")) {
                byte[] audioBytes = response.body() != null ? response.body().bytes() : new byte[0];
                log.info("TTS 合成成功，音频大小: {} bytes", audioBytes.length);
                return audioBytes;
            }
            String err = response.body() != null ? response.body().string() : "(空响应)";
            log.error("TTS 合成失败，响应: {}", err);
            throw new IOException("TTS failed: " + err);
        }
    }
}
