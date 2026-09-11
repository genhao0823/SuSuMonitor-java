import { fileURLToPath, URL } from 'node:url'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router', 'pinia'],
      dts: 'src/types/auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/components.d.ts'
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      // 本地联调可用 VITE_API_PROXY_TARGET 指向远程后端(如 https://genhaosan.online),
      // 默认仍指向本地 18080;/ws 与 /api 同源走代理,前端 WS 用 window.location.host 拼接。
      // stripOrigin: 浏览器同源 POST 也会携带 Origin: http://127.0.0.1:5173,
      // 远程后端的 CORS 白名单不含本地开发源,Spring CorsFilter 会在业务层之前直接 403,
      // 表现为登录后被全局 403 处理重定向到 /forbidden。本地代理层剥掉 Origin,
      // 让远程后端按非跨域请求处理;仅影响本地 dev server,不改变任何生产行为。
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:18080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('Origin'))
          proxy.on('proxyReqWs', (proxyReq) => proxyReq.removeHeader('Origin'))
        }
      },
      '/ws': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:18080',
        changeOrigin: true,
        ws: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('Origin'))
          proxy.on('proxyReqWs', (proxyReq) => proxyReq.removeHeader('Origin'))
        }
      }
    }
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1024
  }
})