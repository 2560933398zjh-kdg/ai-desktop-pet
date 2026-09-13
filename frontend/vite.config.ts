import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 配置vite
export default defineConfig({
plugins: [vue()],
server: {
port: 5173
 },
base: './' // 配置基础路径，解决路由问题
})
