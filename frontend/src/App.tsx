import { Navigate, Route, Routes } from 'react-router-dom'
import { Sidebar } from './components/Sidebar'
import { useSession } from './hooks/useSession'
import { QueryPage } from './pages/QueryPage'
import { CapturePage } from './pages/CapturePage'
import { LoginPage } from './pages/LoginPage'
import { ReviewListPage } from './pages/ReviewListPage'
import { ReviewDetailPage } from './pages/ReviewDetailPage'
import { MemoryListPage } from './pages/MemoryListPage'
import { MemoryDetailPage } from './pages/MemoryDetailPage'
import { SettingsPage } from './pages/SettingsPage'

/** 백엔드가 로그인 실패 시 붙여 주는 쿼리(`/?login_error=not_allowed`)를 읽는다. */
function loginError(): 'not_allowed' | 'failed' | undefined {
  const value = new URLSearchParams(window.location.search).get('login_error')
  return value === 'not_allowed' || value === 'failed' ? value : undefined
}

export default function App() {
  const { state, logout, retry } = useSession()

  // 세션 확인 전에는 아무 화면도 그리지 않는다 — 이미 로그인한 사용자에게 로그인 화면이 깜빡이면
  // "로그아웃됐나?"로 읽히고, 반대로 앱을 먼저 그리면 빈 목록이 "기억이 없다"로 보인다.
  if (state.kind === 'loading') {
    return (
      <div className="loginwrap">
        <div className="card pad" aria-busy="true">
          세션을 확인하고 있어요…
        </div>
      </div>
    )
  }

  // 인증 실패와 서버 장애를 구분한다 — 장애를 로그인 화면으로 보내면 로그인해도 같은 실패가 반복된다.
  if (state.kind === 'error') {
    return (
      <div className="loginwrap">
        <section className="card pad login">
          <div className="eyebrow">연결 실패</div>
          <h1 className="h1">서버에 닿지 못했어요</h1>
          <p className="lede">{state.message}</p>
          {/* `retry` 는 세션 확인만 다시 한다. `window.location.reload()` 로 앱을 통째로 다시 띄우면
              입력 중이던 상태를 버린다 — capture 붙여넣기 화면에서 세션 확인이 실패한 뒤 재시도하면
              붙여넣은 원문이 날아간다. useSession 이 이 API 를 내주는 이유가 그것이다. */}
          <button className="btn primary" onClick={retry}>
            다시 시도
          </button>
        </section>
      </div>
    )
  }

  if (state.kind === 'anonymous') {
    return <LoginPage error={loginError()} />
  }

  return (
    <div className="layout">
      <Sidebar me={state.me} onLogout={logout} />
      <main className="stage">
        <Routes>
          <Route path="/" element={<QueryPage />} />
          <Route path="/capture" element={<CapturePage />} />
          <Route path="/reviews" element={<ReviewListPage />} />
          <Route path="/reviews/:id" element={<ReviewDetailPage />} />
          <Route path="/memories" element={<MemoryListPage />} />
          <Route path="/memories/:id" element={<MemoryDetailPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
