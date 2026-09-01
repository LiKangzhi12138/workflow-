import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const devServerPort = Number(env.VITE_DEV_SERVER_PORT || 5173)
  const devServerHost = (env.VITE_DEV_SERVER_HOST || '0.0.0.0').trim() || '0.0.0.0'
  const previewHost = (env.VITE_PREVIEW_HOST || devServerHost).trim() || devServerHost
  const devProxyTarget = (env.VITE_DEV_PROXY_TARGET || '').trim()

  const proxy = devProxyTarget
    ? {
        '/api': {
          target: devProxyTarget,
          changeOrigin: true
        }
      }
    : undefined

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: devServerHost,
      port: Number.isFinite(devServerPort) && devServerPort > 0 ? devServerPort : 5173,
      strictPort: true,
      open: env.VITE_DEV_OPEN === 'true',
      proxy
    },
    preview: {
      host: previewHost
    }
  }
})
