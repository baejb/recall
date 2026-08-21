import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

// 포트는 루트 .env 한 곳에서 관리한다: FRONTEND_PORT(dev 서버) · BACKEND_PORT(/api 프록시 대상).
// (프론트가 백엔드 호스트를 하드코딩하지 않는다 — dev는 vite 프록시, 배포는 nginx.)
export default defineConfig(({ mode }) => {
  const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const env = loadEnv(mode, rootDir, '')
  const frontendPort = Number(env.FRONTEND_PORT ?? 3000)
  const backendPort = env.BACKEND_PORT ?? '8080'
  return {
    plugins: [react()],
    server: {
      port: frontendPort,
      proxy: {
        '/api': {
          target: `http://localhost:${backendPort}`,
          changeOrigin: true,
        },
      },
    },
  }
})
