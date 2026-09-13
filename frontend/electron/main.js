// Electron 的核心 API、文件系统和路径模块是应用的基础设施
const { app, BrowserWindow, ipcMain, globalShortcut, Tray, Menu, nativeImage } = require("electron")
const fs = require("fs")
const path = require("path")

// 配置文件路径：用户数据目录下的 cyberpet-config.json
const configPath = path.join(app.getPath("userData"), "cyberpet-config.json")

// 读取配置文件，解析失败时返回空对象
function loadConfig() {
  try { return JSON.parse(fs.readFileSync(configPath, "utf8")) } catch { return {} }
}

// 将配置对象序列化为 JSON 并写入文件
function saveConfig(cfg) { fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2)) }

// 共享对话数据存储（宠物窗口写入，设置窗口读取）
let conversation = []

// 全局窗口和组件引用：主窗口、设置窗口、托盘、当前快捷键
let mainWin = null, settingsWin = null, tray = null, currentShortcut = null

// 判断是否为开发模式：通过环境变量 NODE_ENV 或命令行参数 --dev
const isDev = process.env.NODE_ENV === "dev" || process.argv.includes("--dev")

// 注册全局快捷键：先注销旧快捷键，再注册新快捷键
// 触发时向主窗口发送 toggle-recording 消息
function registerShortcut(shortcut) {
  if (currentShortcut) globalShortcut.unregister(currentShortcut)
  try {
    globalShortcut.register(shortcut, () => {
      if (mainWin && !mainWin.isDestroyed()) {
        mainWin.show()
        mainWin.webContents.send("voice-toggle")
      }
    })
    currentShortcut = shortcut
    console.log("Shortcut registered:", shortcut)
  } catch (e) {
    console.error("Failed to register shortcut:", shortcut, e.message)
  }
}

// 加载页面：开发模式使用本地服务器，生产模式加载打包后的文件
// hash 参数用于路由跳转（如 #settings）
function loadPage(win, hash) {
  const url = isDev ? "http://localhost:5173" : `file://${path.join(__dirname, "..", "dist", "index.html")}`
  win.loadURL(url + (hash || ""))
}

// 打开设置窗口：如果窗口已存在则显示并聚焦，否则创建新窗口
function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.show()
    settingsWin.focus()
    return
  }
  settingsWin = new BrowserWindow({
    width: 420, height: 580,
    resizable: false,
    frame: false,
    autoHideMenuBar: true,
    webPreferences: { nodeIntegration: true, contextIsolation: false }
  })
  settingsWin.setMenu(null)
  settingsWin.setBackgroundColor("#1a1a2e")
  loadPage(settingsWin, "#settings")
  // close 事件时阻止关闭，改为隐藏窗口（保持单例）
  settingsWin.on("close", (e) => {
    e.preventDefault()
    settingsWin.hide()
  })
  settingsWin.on("closed", () => {
    settingsWin = null
  })
}

// 创建系统托盘图标：加载图标、设置右键菜单、双击打开设置
function createTray() {
  const iconPath = path.join(__dirname, "tray-icon.png")
  const icon = nativeImage.createFromPath(iconPath)
  tray = new Tray(icon.resize({ width: 16, height: 16 }))
  const menu = Menu.buildFromTemplate([
    { label: "打开设置", click: openSettings },
    { type: "separator" },
    { label: "退出", click: () => { app.quit() } }
  ])
  tray.setContextMenu(menu)
  tray.setToolTip("CyberPet")
  tray.on("double-click", openSettings)
}

// 创建主窗口（宠物窗口）：透明无边框、置顶、不显示任务栏
function createWindow() {
  mainWin = new BrowserWindow({
    width: 280, height: 280,
    transparent: true, frame: false, thickFrame: false, 
    alwaysOnTop: true, resizable: false,
    skipTaskbar: true, title: "",
    type: "toolbar",
    focusable: false,
    titleBarStyle: "hidden",
    acceptFirstMouse: true,
    hasShadow: false,
    backgroundColor: "#00000000",
    webPreferences: { nodeIntegration: true, contextIsolation: false }
  })

  // 加载主页面
  loadPage(mainWin, "")

  // 设置窗口始终置顶（screen-saver 级别最高）、多桌面可见、透明背景
  mainWin.setAlwaysOnTop(true, "screen-saver")
  mainWin.setVisibleOnAllWorkspaces(true)
  mainWin.setBackgroundColor("#00000000")
  mainWin.setMenu(null)

  // Windows 平台下窗口事件处理：保持透明背景
  mainWin.on("focus", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("blur", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("show", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("resize", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  // 阻止页面标题更新（保持空标题）
  mainWin.on("page-title-updated", (e) => e.preventDefault())

  // 加载保存的快捷键配置，默认为 Ctrl+Alt+L
  const cfg = loadConfig()
  const sc = cfg.shortcut || "Ctrl+Alt+L"
  registerShortcut(sc)

  // 创建系统托盘
  createTray()
}

// 应用就绪后创建主窗口
app.whenReady().then(createWindow)

// IPC 事件：关闭应用
ipcMain.on("close-window", () => { globalShortcut.unregisterAll(); app.quit() })

// IPC 事件：隐藏设置窗口
ipcMain.on("hide-settings-window", () => {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.hide()
  }
})

// IPC 事件：调整宠物窗口大小
ipcMain.on("resize-pet-window", (event, w, h) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win) win.setBounds({ width: w, height: h })
})

// IPC 事件：移动宠物窗口（相对移动）
ipcMain.on("move-window", (event, dx, dy) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win) {
    const [wx, wy] = win.getPosition()
    win.setPosition(Math.round(wx + dx), Math.round(wy + dy))
  }
})

// 快捷键相关 IPC
ipcMain.on("get-shortcut", (event) => {
  const cfg = loadConfig()
  event.returnValue = cfg.shortcut || "Ctrl+Alt+L"
})

ipcMain.on("set-shortcut", (event, newShortcut) => {
  const cfg = loadConfig()
  cfg.shortcut = newShortcut
  saveConfig(cfg)
  registerShortcut(newShortcut)
})

// 对话数据 IPC — 增量同步：只传新增消息，主进程 append，避免全量覆盖丢消息
ipcMain.on("conv-update", (event, msg) => {
  conversation.push(msg)
  const sender = event.sender
  // 转发新消息给非发送方的窗口
  if (settingsWin && !settingsWin.isDestroyed() && settingsWin.webContents !== sender) {
    settingsWin.webContents.send("conv-update", msg)
  }
  if (mainWin && !mainWin.isDestroyed() && mainWin.webContents !== sender) {
    mainWin.webContents.send("conv-update", msg)
  }
})

// 使用 invoke/handle 异步模式（取代已废弃的 sendSync + event.returnValue）
ipcMain.handle("get-conversation", () => {
  return conversation
})

// 应用退出前注销所有全局快捷键
app.on("will-quit", () => { globalShortcut.unregisterAll() })

// 所有窗口关闭时退出应用（macOS 除外，保持后台运行）
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit() })