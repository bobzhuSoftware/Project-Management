import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backendPort = Number(process.env.BACKEND_PORT) || 8090
const frontendPort = Number(process.env.PM_FRONTEND_PORT) || 5180
const backendTarget = `http://127.0.0.1:${backendPort}`

export default defineConfig({
  plugins: [react()],
  server: {
    port: frontendPort,
    strictPort: true,
    host: '127.0.0.1',
    hmr: { overlay: true },
    watch: {
      // Useful when files live on a slow/remote/synced drive.
      usePolling: false,
    },
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/sse': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
})
