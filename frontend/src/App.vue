<template>
  <!-- 音频元素：语音合成播放 -->
  <audio ref="audioPlayer" @ended="onAudioEnd"></audio>
  <!-- ========== 设置窗口 ========== -->
  <div v-if="isSettings" class="settings-window">
    <div class="settings-header" @mousedown="onDragStart">
      <span class="settings-title">设置</span>
      <button class="close-btn" @click="closeSettings" @mousedown.stop>×</button>
    </div>
    <div class="settings-tabs">
      <button
        class="tab-btn"
        :class="{ active: settingsTab === 'chat' }"
        @click="settingsTab = 'chat'"
      >聊天记录</button>
      <button
        class="tab-btn"
        :class="{ active: settingsTab === 'shortcut' }"
        @click="settingsTab = 'shortcut'"
      >快捷键</button>
    </div>
    <div class="settings-content">
      <div v-if="settingsTab === 'chat'" class="tab-content chat-tab">
        <!-- 消息列表 -->
        <div class="chat-messages" ref="chatMessagesRef">
          <div v-for="(msg, i) in displayConversation" :key="i"
               :class="['chat-bubble', msg.role === 'user' ? 'bubble-user' : 'bubble-pet']">
            {{ msg.text }}
          </div>
          <div v-if="chatLoading" class="chat-bubble bubble-pet chat-typing">
            <span class="typing-dots">
              <span class="typing-dot" />
              <span class="typing-dot" />
              <span class="typing-dot" />
            </span>
            <span class="typing-text">正在思考...</span>
          </div>
        </div>
        <!-- 输入区 -->
        <div class="chat-input-area">
          <input
            v-model="chatInput"
            class="chat-input"
            placeholder="输入消息..."
            @keydown.enter="sendTextMessage"
            :disabled="chatLoading"
          />
          <button
            class="chat-send-btn"
            @click="sendTextMessage"
            :disabled="chatLoading || !chatInput.trim()"
          >发送</button>
        </div>
      </div>
      <div v-if="settingsTab === 'shortcut'" class="tab-content shortcut-tab">
        <div class="shortcut-current">
          当前快捷键 <span class="key-badge">{{ currentShortcut }}</span>
        </div>
        <div class="shortcut-actions">
          <button v-if="!capturing" class="shortcut-btn" @click="startCapture">
            设置快捷键
          </button>
          <span v-if="capturing" class="capturing-text">正在监听按键...</span>
          <template v-if="capturedKey">
            <div class="captured-info">
              新快捷键: <span class="key-badge">{{ capturedKey }}</span>
            </div>
            <button class="shortcut-btn save-btn" @click="saveShortcut">保存</button>
          </template>
        </div>
      </div>
    </div>
  </div>

  <!-- ========== 宠物窗口 ========== -->
  <div v-else class="pet-window">
    <div
      class="live2d-wrapper"
      :style="{ width: modelConfig.canvas.width + 'px', height: modelConfig.canvas.height + 'px' }"
    >
      <Live2DCanvas
        ref="live2dCanvasRef"
        :width="modelConfig.canvas.width"
        :height="modelConfig.canvas.height"
        :modelName="modelConfig.modelName"
        :modelFile="modelConfig.modelFile"
        :scale="modelConfig.scale"
        :xMultiplier="modelConfig.position.xMultiplier"
        :yMultiplier="modelConfig.position.yMultiplier"
        :debugCanvasBg="modelConfig.debugCanvasBg"
        @loaded="onLoaded"
        @error="onError"
      />
      <!-- 浮装对话气泡 -->
      <div v-if="hintText" class="speech-hint">{{ hintText }}</div>
      <!--
        透明拖拽 + 眼球跟踪层
        KEY: 不使用 -webkit-app-region: drag
        纯 JS mousedown/mousemove/mouseup 实现拖拽，与眼球跟踪 @mousemove 互不冲突
      -->
      <div
        class="pet-drag-region"
        @mousemove="onMouseMove"
        @mousedown="onDragStart"
      ></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import Live2DCanvas from './components/Live2DCanvas.vue'
import { modelConfig } from './config'
import toWav from "audiobuffer-to-wav"
import hark from "hark"

// ========== 窗口模式：设置 / 宠物 ==========
const isSettings = window.location.hash === "#settings"

// ========== 设置窗口 Tab 切换 ==========
const settingsTab = ref("chat")

// 设置窗口关闭：通过 IPC 通知主进程隐藏窗口
function closeSettings() {
  if (ipcRenderer) {
    ipcRenderer.send("hide-settings-window")
  }
}

// ========== 快捷键设置 ==========
const currentShortcut = ref("Ctrl+Alt+L")
const capturing = ref(false)
const capturedKey = ref("")

let _captureHandler: ((e: KeyboardEvent) => void) | null = null

function stopCapture() {
  if (_captureHandler) {
    document.removeEventListener("keydown", _captureHandler, true)
    _captureHandler = null
  }
  capturing.value = false
}

function startCapture() {
  stopCapture()
  capturing.value = true
  capturedKey.value = ""

  _captureHandler = (e: KeyboardEvent) => {
    e.preventDefault()
    e.stopPropagation()

    // Escape 取消捕获
    if (e.key === "Escape") {
      stopCapture()
      return
    }

    // 单独按修饰键不触发
    if (["Control", "Alt", "Shift", "Meta"].includes(e.key)) return

    const parts: string[] = []
    if (e.ctrlKey) parts.push("Ctrl")
    if (e.altKey) parts.push("Alt")
    if (e.shiftKey) parts.push("Shift")
    if (e.metaKey) parts.push("Meta")
    // 按键名标准化：单字母大写，功能键保持原样
    parts.push(e.key.length === 1 ? e.key.toUpperCase() : e.key)

    capturedKey.value = parts.join("+")
  }

  document.addEventListener("keydown", _captureHandler, true)
}

function saveShortcut() {
  if (!capturedKey.value) return
  if (ipcRenderer) {
    ipcRenderer.send("set-shortcut", capturedKey.value)
  }
  currentShortcut.value = capturedKey.value
  stopCapture()
}

// 设置窗口：加载当前快捷键 + 同步语音对话到聊天记录
onMounted(() => {
  if (!isSettings) return
  if (ipcRenderer?.sendSync) {
    const sc = ipcRenderer.sendSync("get-shortcut")
    if (sc) currentShortcut.value = sc
  }
  ipcRenderer.on("conv-update", (_event: any, msg: Message) => {
    conversation.value.push(msg)
    scrollChatToBottom()
  })
})

// ========== 聊天功能 ==========
interface Message {
  role: "user" | "pet"
  text: string
}

const conversation = ref<Message[]>([])
const chatInput = ref("")
const chatLoading = ref(false)
const chatMessagesRef = ref<HTMLDivElement>()

// ===== 语音相关状态 =====
const isRecording = ref(false)
const audioPlayer = ref<HTMLAudioElement>()
let mediaRecorder: MediaRecorder | null = null
let audioChunks: Blob[] = []
let audioQueue: any[] = []
let maxRecordTimer = 0
let harkInstance: any = null
let lipSyncTimer: number | null = null
const loading = ref(false)
let sseAbort: AbortController | null = null

// ===== 录音 =====
async function startRecording() {
  if (isRecording.value) return
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaRecorder = new MediaRecorder(stream, { mimeType: "audio/webm" })
    audioChunks = []
    mediaRecorder.ondataavailable = e => audioChunks.push(e.data)
    mediaRecorder.onstop = () => {
      stream.getTracks().forEach(t => t.stop())
      clearSilenceDetection()
      hintText.value = ""
      sendToBackend()
    }
    mediaRecorder.start()
    isRecording.value = true
    hintText.value = "聆听中..."
    startSilenceDetection(stream)
    maxRecordTimer = window.setTimeout(() => { if (isRecording.value) stopRecording() }, 15000)
  } catch (e) {
    alert("无法访问麦克风，请检查权限设置")
  }
}

function stopRecording() {
  if (maxRecordTimer) { clearTimeout(maxRecordTimer); maxRecordTimer = 0 }
  if (mediaRecorder && mediaRecorder.state === "recording") {
    mediaRecorder.stop()
    isRecording.value = false
  }
}

// ===== 静音检测：hark 库，2 秒无声自动停止 =====
function startSilenceDetection(stream: MediaStream) {
  clearSilenceDetection()
  harkInstance = hark(stream, { threshold: -40, interval: 100 })
  harkInstance.on('stopped_speaking', () => {
    if (isRecording.value) stopRecording()
  })
}

function clearSilenceDetection() {
  if (harkInstance) { harkInstance.stop(); harkInstance = null }
}

// ===== WebM → WAV 转换（audiobuffer-to-wav 库），重采样到 16000Hz 匹配后端 ASR =====
async function webmToWav(blob: Blob): Promise<Blob> {
  const ctx = new AudioContext()
  const abuf = await ctx.decodeAudioData(await blob.arrayBuffer())
  ctx.close()
  // 浏览器 AudioContext 默认 44100/48000Hz，必须降采样到 16000Hz 才能被百度 ASR 正确识别
  const targetRate = 16000
  const offlineCtx = new OfflineAudioContext(1, abuf.duration * targetRate, targetRate)
  const source = offlineCtx.createBufferSource()
  source.buffer = abuf
  source.connect(offlineCtx.destination)
  source.start()
  const resampled = await offlineCtx.startRendering()
  return new Blob([toWav(resampled)], { type: 'audio/wav' })
}

// ===== SSE 流解析 =====
async function parseSSEStream(res: Response, onEvent: (event: string, data: any) => void) {
  const reader = res.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ""

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: true })

    // 按 "\n\n" 分割事件块
    const parts = buffer.split("\n\n")
    buffer = parts.pop() || ""

    for (const part of parts) {
      if (!part.trim()) continue
      const lines = part.split("\n")
      let event = "message"
      let dataStr = ""

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event = line.slice(6).trim()
        } else if (line.startsWith("data:")) {
          dataStr += line.slice(5)
        }
      }

      if (dataStr) {
        try {
          const obj = JSON.parse(dataStr)
          onEvent(event, obj)
        } catch {
          // parse 失败跳过
        }
      }
    }

    if (done) break
  }
}

// ===== 语音发送 =====
const VOICE_API = "http://localhost:8910/api/voice-chat"

async function sendToBackend() {
  if (audioChunks.length === 0) return
  const webmBlob = new Blob(audioChunks, { type: "audio/webm" })
  audioChunks = []

  // 用户占位
  conversation.value.push({ role: "user", text: "语音识别中..." })
  const userIdx = conversation.value.length - 1
  // AI 占位
  conversation.value.push({ role: "pet", text: "思考中..." })
  let petIdx = conversation.value.length - 1
  hintText.value = "..."
  notifyConvUpdate({ role: "pet", text: "思考中..." })

  try {
    const wavBlob = await webmToWav(webmBlob)
    const formData = new FormData()
    formData.append("audio", wavBlob, "recording.wav")

    sseAbort = new AbortController()
    const response = await fetch(VOICE_API, {
      method: "POST",
      body: formData,
      signal: sseAbort.signal,
    })

    if (!response.ok || !response.body) throw new Error("语音请求失败")

    let firstAudio = true

    await parseSSEStream(response, (event, p) => {
      if (event === "userText") {
        loading.value = false
        if (p.text && p.text !== "") {
          conversation.value[userIdx] = { role: "user", text: p.text }
          notifyConvUpdate({ role: "user", text: p.text })
        } else {
          // 删除用户占位
          conversation.value.splice(userIdx, 1)
          petIdx--
          // 删除"思考中..."占位
          conversation.value.splice(petIdx, 1)
          hintText.value = ""
          loading.value = false
        }
      } else if (event === "audio") {
        if (firstAudio) {
          firstAudio = false
          // 删除"思考中..."占位
          conversation.value.splice(petIdx, 1)
          hintText.value = ""
        }
        if (p.data && p.data !== "") {
          const wasEmpty = audioQueue.length === 0
          audioQueue.push(p)
          if (wasEmpty) playNextInQueue()
        }
      } else if (event === "done") {
        loading.value = false
      } else if (event === "error") {
        conversation.value.push({ role: "pet", text: "语音出错了：" + p.message })
        notifyConvUpdate({ role: "pet", text: "语音出错了：" + p.message })
        loading.value = false
      }
    })
  } catch (err: any) {
    if (err?.name === "AbortError") return
    conversation.value.push({ role: "pet", text: "网络好像不太好，请稍后再试" })
    notifyConvUpdate({ role: "pet", text: "网络好像不太好，请稍后再试" })
    loading.value = false
  }
}

// ===== 唇形同步（占位，第4课实现） =====
function startLipSync(_audio: HTMLAudioElement) {}
function stopLipSync() {}

// ===== 音频播放队列 =====
function playNextInQueue() {
  if (audioQueue.length === 0) { hintText.value = ""; return }
  const item = audioQueue[0]
  hintText.value = item.text
  if (item.text) {
    conversation.value.push({ role: "pet", text: item.text })
    notifyConvUpdate({ role: "pet", text: item.text })
  }
  playBase64Audio(item.data)
}

function onAudioEnd() { audioQueue.shift(); playNextInQueue() }

function playBase64Audio(b64: string) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  const blob = new Blob([bytes], { type: "audio/mp3" })
  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  audio.onended = onAudioEnd
  audio.play().catch(() => {})
}

// 合并连续同角色消息，减少碎片气泡
const displayConversation = computed<Message[]>(() => {
  const result: Message[] = []
  for (const msg of conversation.value) {
    const last = result[result.length - 1]
    if (last && last.role === msg.role) {
      last.text += msg.text
    } else {
      result.push({ ...msg })
    }
  }
  return result
})

function scrollChatToBottom() {
  nextTick(() => {
    const el = chatMessagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 按标点或满20字截断
function splitByPunctuation(text: string): string[] {
  const segments: string[] = []
  let buffer = ""
  for (const ch of text) {
    buffer += ch
    if (/[。！？\n]/.test(ch) || buffer.length >= 20) {
      segments.push(buffer)
      buffer = ""
    }
  }
  if (buffer) segments.push(buffer)
  return segments
}

// 剥离 SSE "data:" 前缀：Spring WebFlux 编码为 "data:值"（冒号后无空格）
// 同时兼容 "data: "（有空格）的格式
function trimSseLine(line: string): string {
  return line.startsWith("data:") ? line.slice(5).trimStart() : line
}

async function sendTextMessage() {
  const text = chatInput.value.trim()
  if (!text || chatLoading.value) return
  chatInput.value = ""
  chatLoading.value = true

  // 用户消息
  conversation.value.push({ role: "user", text })
  notifyConvUpdate({ role: "user", text })
  scrollChatToBottom()

  try {
    const response = await fetch("http://localhost:8910/api/text-chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    })
    if (!response.ok || !response.body) throw new Error("请求失败")

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ""
    let fullText = ""
    let doneReceived = false

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        // 流结束时处理 buffer 中剩余内容
        buffer += decoder.decode()
        const lines = buffer.split("\n")
        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue
          if (trimmed === "data:[DONE]" || trimmed === "data: [DONE]") break
          fullText += trimSseLine(trimmed)
        }
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split("\n")
      // 最后一行可能不完整，保留在 buffer 中
      buffer = lines.pop() || ""
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        if (trimmed === "data:[DONE]" || trimmed === "data: [DONE]") {
          doneReceived = true
          break
        }
        fullText += trimSseLine(trimmed)
      }
      if (doneReceived) break // 已收到 [DONE]
    }

    if (fullText) {
      const segments = splitByPunctuation(fullText)
      for (const seg of segments) {
        conversation.value.push({ role: "pet", text: seg })
        notifyConvUpdate({ role: "pet", text: seg })
      }
      scrollChatToBottom()
    }
  } catch (err) {
    const errMsg: Message = { role: "pet", text: "抱歉，连接失败，请稍后重试。" }
    conversation.value.push(errMsg)
    notifyConvUpdate(errMsg)
    console.error("chat error:", err)
  } finally {
    chatLoading.value = false
  }
}

// ========== Electron IPC ==========
const { ipcRenderer } = (window as any).require?.("electron") ?? ({} as any)

// ========== 对话气泡 ==========
const hintText = ref("")

// ========== 气泡播放队列 ==========
let bubbleQueue: string[] = []
let bubbleTimer: number | null = null
let bubbleEmptyCount = 0

// 计算气泡停留时长：中文~170ms/字，英文/数字~50ms/字，范围[3000, 8000]ms
function calcBubbleDuration(text: string): number {
  let duration = 0
  for (const ch of text) {
    duration += /[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/.test(ch) ? 170 : 50
  }
  return Math.max(3000, Math.min(8000, duration))
}

// 显示队列中的下一条气泡
function showNextBubble() {
  if (bubbleTimer) {
    clearTimeout(bubbleTimer)
    bubbleTimer = null
  }
  if (bubbleQueue.length === 0) {
    bubbleEmptyCount++
    if (bubbleEmptyCount >= 10) {
      hintText.value = ""
      bubbleEmptyCount = 0
      return
    }
    bubbleTimer = window.setTimeout(() => {
      showNextBubble()
    }, 500)
    return
  }
  bubbleEmptyCount = 0
  const text = bubbleQueue.shift()!
  hintText.value = text
  bubbleTimer = window.setTimeout(() => {
    showNextBubble()
  }, calcBubbleDuration(text))
}

// 增量同步：只传一条新增消息给主进程（P2: 避免全量覆盖丢消息, P3: 避免深拷贝全量）
function notifyConvUpdate(msg: Message) {
  if (ipcRenderer) {
    ipcRenderer.send("conv-update", JSON.parse(JSON.stringify(msg)))
  }
}

// ========== Live2DCanvas 引用 ==========
const live2dCanvasRef = ref<InstanceType<typeof Live2DCanvas>>()

function onLoaded(name: string) {
  console.log('Live2D model loaded:', name)
}

function onError(err: any) {
  console.error('Live2D model error:', err)
}

// ========== 启动：宠物窗口 IPC 同步 + 历史加载 ==========
onMounted(() => {
  if (!isSettings && ipcRenderer) {
    // 通知主进程调整窗口尺寸
    ipcRenderer.send("resize-pet-window", modelConfig.canvas.width, modelConfig.canvas.height)
    // 语音切换快捷键
    ipcRenderer.on("voice-toggle", () => {
      isRecording.value ? stopRecording() : startRecording()
    })
  }
})

// 宠物窗口：注册 conv-update 监听 + 加载历史对话
onMounted(async () => {
  if (isSettings || !ipcRenderer) return

  // P0: 先注册监听、再加载历史，消除竞态窗口
  const syncHandler = (_event: any, msg: Message) => {
    conversation.value.push(msg)
    if (msg.role === "pet") {
      bubbleQueue.push(msg.text)
      // 取消轮询定时器，直接启动播放
      if (bubbleTimer) {
        clearTimeout(bubbleTimer)
        bubbleTimer = null
      }
      bubbleEmptyCount = 0
      showNextBubble()
    }
  }
  ipcRenderer.on("conv-update", syncHandler)
  // 保存引用用于卸载时清理
  ;(window as any).__convSyncHandler = syncHandler

  // P1: 使用 invoke/handle 异步模式，替代废弃的 sendSync
  try {
    const history: Message[] = await ipcRenderer.invoke("get-conversation")
    for (const msg of history) {
      conversation.value.push(msg)
      if (msg.role === "pet") {
        bubbleQueue.push(msg.text)
      }
    }
    if (bubbleQueue.length > 0 && !bubbleTimer) {
      showNextBubble()
    }
  } catch (e) {
    console.error("Failed to load conversation history:", e)
  }
})

// 宠物窗口：模型加载后推送问候气泡
onMounted(() => {
  if (isSettings) return
  const checkModel = setInterval(() => {
    if (live2dCanvasRef.value?.live2d) {
      clearInterval(checkModel)
      bubbleQueue.push("你好呀！我是你的桌面宠物~")
      if (!bubbleTimer) showNextBubble()
    }
  }, 300)
})

// P1: 组件卸载时清理定时器和 IPC 监听，避免内存泄漏
onUnmounted(() => {
  if (bubbleTimer) {
    clearTimeout(bubbleTimer)
    bubbleTimer = null
  }
  if (ipcRenderer && (window as any).__convSyncHandler) {
    ipcRenderer.removeListener("conv-update", (window as any).__convSyncHandler)
    delete (window as any).__convSyncHandler
  }
  // 清理快捷键捕获监听器
  if (_captureHandler) {
    document.removeEventListener("keydown", _captureHandler, true)
    _captureHandler = null
  }
  // 清理语音切换监听
  ipcRenderer?.removeAllListeners?.("voice-toggle")
})

// ========== 眼球跟踪 ==========
function onMouseMove(e: MouseEvent) {
  const lm = live2dCanvasRef.value?.live2d
  if (lm) {
    lm.lookAt(e.clientX, e.clientY)
  }
}

// ========== 窗口拖拽（纯 JS，不用 -webkit-app-region） ==========
let isDragging = false
let dragStartX = 0
let dragStartY = 0

function onDragMove(e: MouseEvent) {
  if (!isDragging) return
  const dx = e.screenX - dragStartX
  const dy = e.screenY - dragStartY
  if (dx === 0 && dy === 0) return
  // 更新起始坐标，避免累积
  dragStartX = e.screenX
  dragStartY = e.screenY
  // 通过 IPC 通知主进程相对移动窗口
  ipcRenderer.send("move-window", dx, dy)
}

function onDragEnd() {
  if (!isDragging) return
  isDragging = false
  document.removeEventListener("mousemove", onDragMove)
  document.removeEventListener("mouseup", onDragEnd)
}

function onDragStart(e: MouseEvent) {
  // 只在左键按下时触发拖拽
  if (e.button !== 0) return
  isDragging = true
  dragStartX = e.screenX
  dragStartY = e.screenY
  document.addEventListener("mousemove", onDragMove)
  document.addEventListener("mouseup", onDragEnd)
}
</script>

<style>
/* 全局重置：消除窗口默认边距 */
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: transparent;
  user-select: none;
}

#app {
  width: 100%;
  height: 100%;
}
</style>

<style scoped>
/* ====== 设置窗口：暗色主题 ====== */
.settings-window {
  width: 420px;
  height: 100%;
  background: #1a1a2e;
  color: #c8d0d8;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  user-select: none;
}

.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #2a2a40;
  flex-shrink: 0;
  cursor: grab;
}

.settings-header:active {
  cursor: grabbing;
}

.settings-title {
  font-size: 16px;
  font-weight: 600;
}

.close-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: #c8d0d8;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.settings-tabs {
  display: flex;
  border-bottom: 1px solid #2a2a40;
  flex-shrink: 0;
}

.tab-btn {
  flex: 1;
  padding: 10px 0;
  border: none;
  background: transparent;
  color: #7a8290;
  font-size: 14px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color 0.2s, border-color 0.2s;
}

.tab-btn:hover {
  color: #a0aab4;
}

.tab-btn.active {
  color: #a8d8ff;
  border-bottom-color: #a8d8ff;
}

.settings-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.tab-content {
  padding: 20px 16px;
  font-size: 14px;
  line-height: 1.6;
  color: #8a929e;
  overflow-y: auto;
}

/* ====== 聊天 Tab ====== */
.chat-tab {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 12px 4px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chat-bubble {
  max-width: 78%;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  animation: bubble-in 0.25s ease-out both;
}

@keyframes bubble-in {
  0% { opacity: 0; transform: translateY(6px); }
  100% { opacity: 1; transform: translateY(0); }
}

.bubble-user {
  align-self: flex-end;
  background: #3b82f6;
  color: #fff;
  border-radius: 14px 14px 4px 14px;
}

.bubble-pet {
  align-self: flex-start;
  background: #2a2a3e;
  color: #c8d0d8;
  border-radius: 14px 14px 14px 4px;
}

/* 打字动画 */
.chat-typing {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
}

.typing-dots {
  display: flex;
  align-items: center;
  gap: 3px;
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a8d8ff;
  animation: typing-bounce 1.4s ease-in-out infinite both;
}

.typing-dot:nth-child(1) { animation-delay: 0s; }
.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing-bounce {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.6); }
  40% { opacity: 1; transform: scale(1); }
}

.typing-text {
  font-size: 12px;
  color: #8a929e;
  animation: typing-fade 1.4s ease-in-out infinite;
}

@keyframes typing-fade {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 1; }
}

/* 输入区 */
.chat-input-area {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #2a2a40;
  flex-shrink: 0;
}

.chat-input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #2a2a40;
  border-radius: 8px;
  background: #12122a;
  color: #c8d0d8;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
}

.chat-input::placeholder {
  color: #5a6070;
}

.chat-input:focus {
  border-color: #3b82f6;
}

.chat-send-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: #3b82f6;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
  flex-shrink: 0;
}

.chat-send-btn:hover:not(:disabled) {
  background: #2563eb;
}

.chat-send-btn:disabled {
  background: #1e3a5f;
  color: #5a6a7a;
  cursor: not-allowed;
}

/* ====== 快捷键 Tab ====== */
.shortcut-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.shortcut-current {
  font-size: 14px;
  color: #c8d0d8;
}

.key-badge {
  display: inline-block;
  padding: 2px 10px;
  background: rgba(59, 130, 246, 0.2);
  border: 1px solid rgba(59, 130, 246, 0.3);
  border-radius: 6px;
  color: #60a5fa;
  font-family: "Consolas", "Monaco", "Courier New", monospace;
  font-size: 13px;
}

.shortcut-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.shortcut-btn {
  align-self: flex-start;
  padding: 8px 20px;
  border: 1px solid #3b82f6;
  border-radius: 8px;
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.shortcut-btn:hover {
  background: rgba(59, 130, 246, 0.3);
}

.shortcut-btn.save-btn {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}

.shortcut-btn.save-btn:hover {
  background: #2563eb;
}

.capturing-text {
  color: #fbbf24;
  font-size: 14px;
  animation: capture-blink 0.8s ease-in-out infinite;
}

@keyframes capture-blink {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

.captured-info {
  font-size: 14px;
  color: #c8d0d8;
}

/* ====== 宠物窗口 ====== */
.pet-window {
  /* 不要设置 -webkit-app-region！那会导致所有鼠标事件被系统接管 */
}

.live2d-wrapper {
  position: relative;
}

/* ====== 浮动对话气泡 ====== */
.speech-hint {
  position: absolute;
  bottom: 65px;
  left: 50%;
  transform: translateX(-50%);
  /* 远高于 canvas 层(1) 和拖拽层(10)，确保气泡不被遮挡 */
  z-index: 60;
  min-width: 80px;
  max-width: 340px;
  padding: 8px 16px;
  background: rgba(0, 0, 0, 0.65);
  border: 1px solid rgba(168, 216, 255, 0.3);
  color: #a8d8ff;
  font-size: 14px;
  line-height: 1.5;
  text-align: center;
  border-radius: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  pointer-events: none;
  /* 弹入动画 */
  animation: bubble-pop-in 0.35s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

@keyframes bubble-pop-in {
  0% {
    opacity: 0;
    transform: translateX(-50%) scale(0.7);
  }
  100% {
    opacity: 1;
    transform: translateX(-50%) scale(1);
  }
}

/*
  透明拖拽 + 眼球跟踪层
  覆盖整个画布区域，inset: 0 填充父容器
  接收所有鼠标事件用于:
  - @mousemove → 眼球跟踪
  - @mousedown → 窗口拖拽（JS实现）
*/
.pet-drag-region {
  position: absolute;
  inset: 0;
  z-index: 10;
  /* 完全透明，不可见但可接收鼠标事件 */
  background: transparent;
  cursor: default;
}
</style>
