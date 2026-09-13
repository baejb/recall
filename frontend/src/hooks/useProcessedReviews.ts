import { useCallback, useEffect, useState } from 'react'
import { getProcessedReviews } from '../api/client'
import { toReview } from '../api/adapter'
import type { Review } from '../types'

/** 로딩·실패·완료를 한 값으로 둔다 — 셋이 화면에서 같아 보이면 서버 장애가 "처리한 게 없다"로 읽힌다. */
type State =
  { kind: 'loading' } | { kind: 'ready'; items: Review[] } | { kind: 'error'; message: string }

/**
 * 처리된(승인·반려) 검토 항목 — 탭을 열 때만 읽는다.
 *
 * 전역 스토어(`RecallProvider`)에 두지 않는 이유: 대기함은 앱이 부팅할 때마다 필요하지만 처리 기록은 사용자가
 * 탭을 열 때만 필요하다. 부팅 경로에 얹으면 아무도 안 보는 목록을 매번 받아 온다.
 *
 * 이펙트 안에서 동기 `setState` 를 하지 않는다(`react-hooks/set-state-in-effect`). 초기값이 `loading`
 * 이라 첫 진입은 그대로 덮이고, 다시 읽을 때의 `loading` 전환은 이벤트 핸들러인 `reload` 가 맡는다.
 * 그 사이에는 이전 목록이 남아 보인다 — 빈 화면으로 깜빡이는 것보다 낫다.
 */
export function useProcessedReviews(enabled: boolean): {
  items: Review[]
  loading: boolean
  error: string | null
  reload: () => void
} {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (!enabled) return
    const ctrl = new AbortController()
    getProcessedReviews(ctrl.signal)
      .then((rows) => setState({ kind: 'ready', items: rows.map(toReview) }))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        setState({
          kind: 'error',
          message: e instanceof Error ? e.message : '처리 기록을 불러오지 못했어요',
        })
      })
    return () => ctrl.abort()
  }, [enabled, tick])

  const reload = useCallback(() => {
    setState({ kind: 'loading' })
    setTick((t) => t + 1)
  }, [])

  return {
    items: state.kind === 'ready' ? state.items : [],
    loading: state.kind === 'loading',
    error: state.kind === 'error' ? state.message : null,
    reload,
  }
}
