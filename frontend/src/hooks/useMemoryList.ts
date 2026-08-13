import { useCallback, useEffect, useRef, useState } from 'react'
import { getMemories } from '../api/client'
import { toMemory } from '../api/adapter'
import type { Memory, MemoryTypeKey } from '../types'
import type { MemoryCounts } from '../api/dto'

/** 목록 유형 필터. 'all' = 전체(필터 없음). */
export type MemoryScope = 'all' | MemoryTypeKey

const PAGE_SIZE = 20
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
  setQuery: (q: string) => void
  setScope: (s: MemoryScope) => void
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
  const [debouncedQuery, setDebouncedQuery] = useState('')
  // 리셋 트리거(reload). 값이 바뀌면 첫 페이지를 다시 읽는다.
  const [reloadKey, setReloadKey] = useState(0)

  const ctrlRef = useRef<AbortController | null>(null)

  // 검색어 디바운스: 타이머 콜백에서만 setState → 첫 페이지 재요청 트리거.
  useEffect(() => {
    const id = setTimeout(() => setDebouncedQuery(query.trim()), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [query])

  const typeParam = scope === 'all' ? undefined : scope

  // 첫 페이지 로드(리셋). query(디바운스)·scope·reloadKey 변경 시. setState는 전부 async 콜백에서만.
  useEffect(() => {
    ctrlRef.current?.abort()
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    getMemories({ q: debouncedQuery || undefined, type: typeParam, limit: PAGE_SIZE }, ctrl.signal)
      .then((page) => {
        setItems(page.items.map(toMemory))
        setNextCursor(page.nextCursor)
        setCounts(page.counts)
        setError(null)
      })
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '기억을 불러오지 못했어요')
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false)
      })
    return () => ctrl.abort()
  }, [debouncedQuery, typeParam, reloadKey])

  const loadMore = useCallback(() => {
    // 이미 진행 중이거나 더 없으면 무시(중복 요청 차단). setState는 이벤트/관찰자 콜백에서 호출 → 허용.
    if (!nextCursor || loadingMore || loading) return
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    setLoadingMore(true)
    getMemories(
      { q: debouncedQuery || undefined, type: typeParam, cursor: nextCursor, limit: PAGE_SIZE },
      ctrl.signal
    )
      .then((page) => {
        // append. counts는 첫 페이지 값 유지(스크롤 응답은 null).
        setItems((prev) => [...prev, ...page.items.map(toMemory)])
        setNextCursor(page.nextCursor)
        setError(null)
      })
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '다음 페이지를 불러오지 못했어요')
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoadingMore(false)
      })
  }, [debouncedQuery, typeParam, nextCursor, loading, loadingMore])

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
    setQuery,
    setScope,
    loadMore,
    reload,
  }
}
