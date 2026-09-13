# frontend — AI 桌宠桌面客户端（cyberpet）

基于 **Vue 3 + Electron + PixiJS** 的 AI 桌面宠物客户端，以 Live2D 形象常驻桌面，支持文本聊天、语音对话、系统托盘与全局快捷键。

## 功能特性

- **Live2D 桌宠**：基于 pixi.js + pixi-live2d-display 渲染，内置多个模型（LLS/LSS、Roxy、纳西妲等）
- **语音交互**：hark 语音活动检测（VAD）+ audiobuffer-to-wav 录音转 WAV，上传后端进行语音对话；播放后端返回的分段 TTS 音频
- **文本聊天**：设置窗口内输入文字，通过 SSE 流式接收回复并展示聊天气泡
- **系统托盘**：Electron Tray 常驻，支持显示/隐藏桌宠
- **全局快捷键**：注册全局录音快捷键（可在设置中自定义），触发时唤起桌宠录音
- **配置持久化**：配置与对话数据存储于用户数据目录 `cyberpet-config.json`

## 技术栈

- Vue 3.5 + Vite 6 + TypeScript
- Electron 38（桌面壳：主窗口、设置窗口、托盘、全局快捷键、IPC）
- pixi.js 7 + pixi-live2d-display（Live2D 渲染）
- hark（语音活动检测）、audiobuffer-to-wav（音频编码）

## 目录结构

```
frontend/
├── package.json               # 依赖与脚本（npm run dev / dev:electron / build）
├── vite.config.ts             # Vite 配置
├── index.html
├── electron/
│   ├── main.js                # Electron 主进程（托盘、全局快捷键、配置读写、窗口管理）
│   └── tray-icon.png          # 托盘图标
├── public/
│   └── live2d/                # Live2D 模型资源（LLS / LSS / Roxy / 纳西妲 等）
└── src/
    ├── main.ts / App.vue      # 应用入口与主界面（设置窗口、聊天、快捷键）
    ├── config.ts              # Live2D 模型与画布配置
    ├── components/
    │   └── Live2DCanvas.vue   # Live2D 画布组件
    └── live2d/
        ├── index.ts
        ├── Live2DManager.ts   # Live2D 模型加载与管理
        └── PixiManager.ts     # PixiJS 渲染管理
```

## 运行方式

```bash
npm install

# 开发模式（Vite 热更新 + Electron）
npm run dev:electron

# 仅启动 Vite（浏览器调试）
npm run dev

# 构建
npm run build
```

## 配置说明

- `src/config.ts`：Live2D 模型名称、缩放、画布尺寸、位置等参数。
- `electron/main.js`：全局快捷键、托盘行为与 `cyberpet-config.json` 配置持久化。
- 后端地址配置见 `src/` 内 API 调用处（默认指向本地 8910 端口）。

## 备注

- 需先启动后端 `Agent` 服务（端口 8910）才能进行对话。
- Live2D 模型资源位于 `public/live2d/`，可替换为其他 `.model3.json` 模型。
