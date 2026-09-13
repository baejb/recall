import { useCallback, useEffect, useRef, useState } from 'react'
import { getMemories, type MemoryStatus } from '../api/client'
import { toMemory } from '../api/adapter'
import type { Memory, MemoryTypeKey } from '../types'
import type { MemoryCounts } from '../api/dto'

/** 목록 유형 필터. 'all' = 전체(필터 없음). */
export type MemoryScope = 'all' | MemoryTypeKey

export const PAGE_SIZE = 20
const SEARCH_DEBOUNCE_MS = 300

export interface MemoryListState {
  items: Memory[]
  counts: MemoryCounts | null
  loading: boolean // 첫 로드 스피너(이후 검색/필터는 in-place 교체)
  loadingMore: boolean // 다음 페이지 로딩(무한 스크롤)
  error: string | null
  hasMore: boolean
  query: string
  scope: MemoryScope
  statusView: MemoryStatus // 조회 중인 상태(active=정상, archived=숨김, incorrect=폐기)
  setQuery: (q: string) => void
  setScope: (s: MemoryScope) => void
  setStatusView: (s: MemoryStatus) => void
  loadMore: () => void
  reload: () => void
}

/**
 * 기억 목록의 서버사이드 키셋 페이지네이션 + 검색/유형필터를 담는 훅(전역 store와 분리).
 *
 * - query/scope 변경 → 리셋 후 첫 페이지 재요청(검색어는 300ms 디바운스). 결과는 in-place 교체.
 * - loadMore() → nextCursor로 다음 페이지 append. cursor가 없으면(hasMore=false) 중단.
 * - 진행 중 요청은 AbortController로 취소해 중복·경합을 막는다. setState는 async 콜백/이벤트
 *   에서만 호출한다(이펙트 본문 동기 setState 회피). 로딩/에러/끝 상태를 모두 노출(조용한 실패 금지).
 */
export function useMemoryList(): MemoryListState {
  const [items, setItems] = useState<Memory[]>([])
  const [counts, setCounts] = useState<MemoryCounts | null>(null)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [query, setQuery] = useState('')
  const [scope, setScope] = useState<MemoryScope>('all')
  const [statusView, setStatusView] = useState<MemoryStatus>('active')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  // 리셋 트리거(reload). 값이 바뀌면 첫 페이지를 다시 읽는다.
  const [reloadKey, setReloadKey] = useState(0)

  // 첫-페이지(리셋)와 다음-페이지(loadMore)는 컨트롤러를 분리한다 — 공유하면 리셋이 진행 중인
  // loadMore 를 abort 해 loadingMore 가 영구 true 로 고착된다(페이지네이션 정지).
  const firstCtrlRef = useRef<AbortController | null>(null)
  const moreCtrlRef = useRef<AbortController | null>(null)
  // 요청 세대(generation). 리셋(첫 페이지 재요청)마다 증가시켜 각 in-flight 요청에 스탬프로 부여한다.
  // abort 가 즉시 반영되지 않아도(이미 도착한 응답 등) 콜백이 자기 세대가 아직 최신인지 확인해,
  // 뒤늦게 끝난 옛 쿼리의 응답이 새 목록을 덮어쓰지 못하게 한다(경합 방어, 조용한 오염 금지).
  // reqGenRef 는 ref 라 리셋 이펙트에서 setState 없이 증가시킬 수 있다(이펙트 본문 동기 setState 회피 규칙).
  const reqGenRef = useRef(0)
  // 현재 nextCursor 가 어느 세대의 첫 페이지에서 나왔는지. 리셋 직후엔 옛 커서가 아직 state 에 남아
  // 있으므로(새 첫 페이지 도착 전), 이 값이 최신 세대와 다르면 loadMore 를 막아 스테일 커서로 엉뚱한
  // 페이지를 append 하는 경합을 차단한다(커서 무효화를 state 가 아닌 세대 비교로).
  const cursorGenRef = useRef(0)

  // 검색어 디바운스: 타이머 콜백에서만 setState → 첫 페이지 재요청 트리거.
  useEffect(() => {
    const id = setTimeout(() => setDebouncedQuery(query.trim()), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [query])

  const typeParam = scope === 'all' ? undefined : scope

  // 첫 페이지 로드(리셋). query(디바운스)·scope·status·reloadKey 변경 시. setState는 전부 async 콜백에서만.
  useEffect(() => {
    firstCtrlRef.current?.abort()
    // 진행 중이던 다음-페이지 요청도 취소한다(옛 쿼리의 페이지가 새 목록에 섞이는 것 방지). 취소되면
    // loadMore 의 finally 가 loadingMore 를 내려주므로(무조건 실행) 스피너가 고착되지 않는다.
    moreCtrlRef.current?.abort()
    // 세대 증가 = 옛 쿼리 커서의 무효화. 새 첫 페이지가 cursorGenRef 를 이 세대로 올리기 전까지
    // loadMore 는 세대 불일치로 막힌다(스테일 커서로 엉뚱한 페이지 append 방지). ref 라 setState 아님.
    const gen = ++reqGenRef.current
    const ctrl = new AbortController()
    firstCtrlRef.current = ctrl
    getMemories(
      { q: debouncedQuery || undefined, type: typeParam, status: statusView, limit: PAGE_SIZE },
      ctrl.signal
    )
      .then((page) => {
        if (gen !== reqGenRef.current) return // 더 새로운 리셋이 이미 시작됨 — 이 응답은 버린다.
        setItems(page.items.map(toMemory))
        setNextCursor(page.nextCursor)
        cursorGenRef.current = gen // 이 세대의 커서가 유효해졌다 → loadMore 허용.
        setCounts(page.counts)
        setError(null)
      })
      .catch((e: unknown) => {
        if (gen !== reqGenRef.current || ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '기억을 불러오지 못했어요')
      })
      .finally(() => {
        if (gen === reqGenRef.current) setLoading(false)
      })
    return () => ctrl.abort()
  }, [debouncedQuery, typeParam, statusView, reloadKey])

  const loadMore = useCallback(() => {
    // 이미 진행 중이거나 더 없으면 무시(중복 요청 차단). setState는 이벤트/관찰자 콜백에서 호출 → 허용.
    // cursorGenRef !== reqGenRef 이면 리셋이 진행 중(옛 커서만 남음) → 새 첫 페이지가 올 때까지 막는다.
    if (!nextCursor || loadingMore || loading || cursorGenRef.current !== reqGenRef.current) return
    const gen = reqGenRef.current
    const ctrl = new AbortController()
    moreCtrlRef.current = ctrl
    setLoadingMore(true)
    getMemories(
      {
        q: debouncedQuery || undefined,
        type: typeParam,
        status: statusView,
        cursor: nextCursor,
        limit: PAGE_SIZE,
      },
      ctrl.signal
    )
      .then((page) => {
        if (gen !== reqGenRef.current) return // 리셋됨 — 옛 쿼리의 페이지를 새 목록에 섞지 않는다.
        // append. counts는 첫 페이지 값 유지(스크롤 응답은 null).
        setItems((prev) => [...prev, ...page.items.map(toMemory)])
        setNextCursor(page.nextCursor)
        setError(null)
      })
      .catch((e: unknown) => {
        if (gen !== reqGenRef.current || ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '다음 페이지를 불러오지 못했어요')
      })
      .finally(() => {
        // aborted(리셋이 취소)여도 무조건 내린다 — 안 그러면 리셋이 이 요청을 취소했을 때
        // loadingMore 가 영구 true 로 고착돼 페이지네이션이 멈춘다.
        setLoadingMore(false)
      })
  }, [debouncedQuery, typeParam, statusView, nextCursor, loading, loadingMore])

  const reload = useCallback(() => setReloadKey((k) => k + 1), [])

  return {
    items,
    counts,
    loading,
    loadingMore,
    error,
    hasMore: nextCursor !== null,
    query,
    scope,
    statusView,
    setQuery,
    setScope,
    setStatusView,
    loadMore,
    reload,
  }
}
