// 引⼊vite客户端类型定义
/// <reference types="vite/client" />
// 定义vue组件类型
declare module '*.vue' {
    import type { DefineComponent } from 'vue' // 引⼊vue组件类型定义
    const component: DefineComponent<{}, {}, any> // 定义vue组件类型
    export default component // 导出vue组件类型
}

declare module "audiobuffer-to-wav" {
    function toWav(buffer: AudioBuffer): ArrayBuffer
    export default toWav
}

declare module "hark" {
    interface HarkOptions { threshold?: number; interval?: number }
    interface HarkInstance {
        on(event: "speaking" | "stopped_speaking", cb: () => void): void
        stop(): void
    }
    function hark(stream: MediaStream, options?: HarkOptions): HarkInstance
    export default hark
}