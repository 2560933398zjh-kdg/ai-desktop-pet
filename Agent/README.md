# Agent — AI 桌宠后端服务

基于 **Spring Boot + Spring AI** 的 AI 桌面宠物后端，接入通义千问大模型，为桌宠客户端提供流式文本对话与"语音识别 → 大模型回复 → 语音合成"的完整语音对话链路。

## 功能特性

- **流式文本对话**：`/api/chat` 基于 SSE（Server-Sent Events）逐字推送回复
- **语音对话**：`/api/voice-chat` 完成 语音 → ASR 识别 → LLM 回复 → 分段 TTS 合成 → SSE 推送 全链路
- **多轮记忆**：基于 Spring AI `MessageChatMemoryAdvisor` + 内存仓库维护会话上下文
- **工具调用**：注册文件操作工具（读取/列出文件）与飞书群机器人工具（发送消息），大模型可自主调用
- **桌宠人设**：通过系统提示词设定 AI 桌宠"小黑"，回复控制在 30 字以内

## 技术栈

- Java 21 + Spring Boot 3.4.7
- Spring AI 1.1.4（OpenAI 兼容协议接入通义千问）
- 阿里云百炼 DashScope（qwen3.7-plus）
- 百度智能云语音 API（ASR 语音识别、TTS 语音合成/音色克隆）
- Reactor（SSE 流式响应）

## 目录结构

```
Agent/
├── pom.xml                            # Maven 配置（Spring AI BOM）
├── src/main/java/com/itheima/agent/
│   ├── AgentApplication.java          # 启动类
│   ├── config/AiConfig.java           # Spring AI OpenAI 兼容客户端配置
│   ├── controller/
│   │   ├── ChatController.java        # /api/chat 流式文本对话（SSE）
│   │   └── VoiceChatController.java   # /api/voice-chat 语音对话（SSE）
│   ├── service/
│   │   ├── ChatService.java           # 对话服务（ChatClient + 记忆 + 工具）
│   │   ├── BaiduSpeechService.java    # 百度语音识别 / 合成 / 音色克隆
│   │   └── AudioSegmentService.java   # 音频分段处理
│   └── tool/
│       ├── FileTools.java             # 文件工具（readFile / listFiles）
│       └── FeishuTool.java            # 飞书群机器人工具（sendFeishuMessage）
└── src/main/resources/application.properties   # 端口、模型、语音、飞书配置
```

## 配置说明（application.properties）

| 配置项 | 说明 |
| --- | --- |
| `server.port` | 服务端口（默认 8910） |
| `spring.ai.openai.api-key` | 阿里云百炼 DashScope API Key |
| `spring.ai.openai.base-url` | DashScope OpenAI 兼容端点 |
| `spring.ai.openai.chat.options.model` | 对话模型（默认 qwen3.7-plus） |
| `bailian.system-prompt` | 桌宠系统提示词（"小黑"人设） |
| `baidu.api-key` / `baidu.secret-key` | 百度语音 API 凭证 |
| `baidu.asr-url` / `baidu.tts-url` | 语音识别 / 合成接口地址 |
| `baidu.tts-voice-id` | TTS 音色 ID（音色克隆） |
| `feishu.webhook-url` | 飞书群机器人 Webhook |

## 运行方式

```bash
mvn spring-boot:run
```

## 核心接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat` | 文本对话，请求体 `{"text": "..."}`，SSE 流式返回 |
| POST | `/api/voice-chat` | 语音对话，multipart 上传 `audio` 文件，SSE 返回文本 + 分段语音 |

## 备注

- 配置文件含真实 API Key / Webhook 地址，请勿公开分享；生产环境建议改为环境变量注入。
- 对话记忆存储在内存中，服务重启后丢失。
