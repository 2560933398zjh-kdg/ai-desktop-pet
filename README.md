# Agentproject — AI 桌面宠物（cyberpet）

一个带 **Live2D 形象** 的 AI 桌面宠物应用，由两部分组成：

- **`Agent/`**：Java Spring Boot 后端，基于 Spring AI 接入通义千问大模型，提供流式文本对话与语音对话能力
- **`frontend/`**：Vue 3 + Electron 桌面客户端，Live2D 桌宠渲染、语音采集播放、系统托盘与全局快捷键

整体工作方式：桌宠（Live2D）常驻桌面 → 用户按全局快捷键说话/输入文字 → 后端 ASR 识别 → 大模型生成回复 → 前端 TTS 语音播放并展示气泡。

## 子项目

| 子目录 | 技术 | 说明 |
| --- | --- | --- |
| `Agent/` | Java 21 + Spring Boot 3.4.7 + Spring AI 1.1.4 | AI 桌宠后端（端口 8910） |
| `frontend/` | Vue 3 + Vite 6 + TypeScript + Electron 38 + PixiJS | 桌宠桌面客户端（Live2D） |

## 快速启动

```bash
# 1. 启动后端（先配置 application.properties 中的 API Key）
cd Agent && mvn spring-boot:run

# 2. 启动前端
cd frontend && npm install && npm run dev:electron
```

详见各子项目 README。
