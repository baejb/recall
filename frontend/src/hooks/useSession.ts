import { useCallback, useEffect, useState } from 'react'
import { ApiRequestError, UNAUTHENTICATED_EVENT, getMe, logout as logoutApi } from '../api/client'
import type { MeResponse } from '../api/dto'

/** 세션 상태. loading 을 따로 두는 이유: 확인 전에 로그인 화면을 깜빡이면 이미 로그인한 사용자에게도 보인다. */
export type SessionState =
  | { kind: 'loading' }
  | { kind: 'authenticated'; me: MeResponse }
  | { kind: 'anonymous' }
  | { kind: 'error'; message: string }

/**
 * 앱 부팅 시 세션을 확인하고, 이후 세션이 끊기면(401) 익명으로 되돌린다.
 *
 * 401 은 어느 호출에서든 날 수 있어서(만료·로그아웃) 화면마다 처리하면 빠뜨리는 곳이 생긴다. 요청 창구가
 * 던지는 `UNAUTHENTICATED_EVENT` 를 여기서 한 번만 듣는다.
 *
 * `/api/me` 는 세션 확인 외에 **CSRF 토큰 쿠키를 받는 계기**이기도 하다 — 그게 없으면 첫 상태변경
 * POST 가 403 이 된다(백엔드는 토큰을 실제로 읽을 때 쿠키를 내려보낸다).
 */
export function useSession(): {
  state: SessionState
  logout: () => Promise<void>
  retry: () => void
} {
  const [state, setState] = useState<SessionState>({ kind: 'loading' })
  const [retryTick, setRetryTick] = useState(0)

  useEffect(() => {
    const ctrl = new AbortController()
    getMe(ctrl.signal)
      .then((me) => setState({ kind: 'authenticated', me }))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        if (e instanceof ApiRequestError && e.isUnauthenticated) {
          setState({ kind: 'anonymous' })
          return
        }
        // 네트워크·서버 장애를 익명으로 취급하면 로그인 화면이 뜨고, 로그인해도 같은 실패가 반복된다.
        // 원인이 다르면 화면도 달라야 한다(조용한 실패 금지).
        setState({
          kind: 'error',
          message: e instanceof Error ? e.message : '세션을 확인할 수 없어요',
        })
      })
    return () => ctrl.abort()
  }, [retryTick])

  useEffect(() => {
    const onUnauthenticated = () => setState({ kind: 'anonymous' })
    window.addEventListener(UNAUTHENTICATED_EVENT, onUnauthenticated)
    return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onUnauthenticated)
  }, [])

  const logout = useCallback(async () => {
    try {
      await logoutApi()
    } finally {
      // 서버 호출이 실패해도 화면은 로그아웃으로 간다 — 세션이 살아 있다면 다음 요청의 401 이 다시 알린다.
      setState({ kind: 'anonymous' })
    }
  }, [])

  const retry = useCallback(() => setRetryTick((t) => t + 1), [])

  return { state, logout, retry }
}
