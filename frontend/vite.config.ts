import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

// 포트는 루트 .env 한 곳에서 관리한다: FRONTEND_PORT(dev 서버) · BACKEND_PORT(프록시 대상).
// (프론트가 백엔드 호스트를 하드코딩하지 않는다 — dev는 vite 프록시, 배포는 nginx.)

// 백엔드가 처리해야 하는 경로. `/api` 외에 **OAuth 경로 두 개**가 반드시 있어야 한다:
//   /oauth2/authorization/google  로그인 시작(브라우저가 직접 여는 주소)
//   /login/oauth2/code/google     Google 콜백 — authorization code 가 실려 온다
// 빠지면 vite 가 `index.html` 을 200 으로 돌려주므로 콘솔·네트워크 탭에 에러가 없고, 로그인 버튼을
// 눌러도 로그인 화면이 다시 그려지기만 한다(조용한 실패). 백엔드 `PUBLIC_PATHS` 에 이 경로를 연 것은
// 백엔드 필터 체인 안에서만 유효해서, 요청이 백엔드까지 오지 못하면 아무 의미가 없다.
const BACKEND_PATHS = ['/api', '/oauth2', '/login']

export default defineConfig(({ mode }) => {
  const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const env = loadEnv(mode, rootDir, '')
  const frontendPort = Number(env.FRONTEND_PORT ?? 3000)
  const backendPort = env.BACKEND_PORT ?? '8080'
  const target = `http://localhost:${backendPort}`
  return {
    plugins: [react()],
    server: {
      port: frontendPort,
      proxy: Object.fromEntries(
        BACKEND_PATHS.map((path) => [path, { target, changeOrigin: true }])
      ),
    },
  }
})
