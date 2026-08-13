import { useCallback, useEffect, useRef, useState } from 'react'
import { getCatalog, getSettings, updateSettings } from '../api/client'
import type { CatalogResponse, SettingsResponse, SettingsUpdateRequest } from '../api/dto'

// 재색인(REINDEXING) 상태를 유한 폴링으로 추적한다. 무한 루프 금지(불변 원칙: 조용한 실패/무한 대기 금지).
const POLL_INTERVAL_MS = 1000
const POLL_MAX_ATTEMPTS = 30

export interface UseSettings {
  settings: SettingsResponse | null
  catalog: CatalogResponse | null
  loading: boolean
  error: string | null
  reload: () => void
  /** 저장 후 갱신된 설정을 반환. status가 REINDEXING이면 내부에서 유한 폴링을 시작해 settings를 갱신한다. */
  save: (body: SettingsUpdateRequest) => Promise<SettingsResponse>
}

/** 설정(모델 provider) 페이지의 페칭·저장·재색인 폴링 로직. 표시는 SettingsPage가 담당. */
export function useSettings(): UseSettings {
  const [settings, setSettings] = useState<SettingsResponse | null>(null)
  const [catalog, setCatalog] = useState<CatalogResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)
  const mounted = useRef(true)

  const stopPoll = useCallback(() => {
    if (pollTimer.current !== null) {
      clearInterval(pollTimer.current)
      pollTimer.current = null
    }
  }, [])

  // 언마운트 정리: 폴링 중단 + 이후 setState 방지.
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      stopPoll()
    }
  }, [stopPoll])

  // 초기 로드(settings + catalog). 이펙트 본문 동기 setState를 피해 async IIFE로 감싼다(await 이후에만
  // setState). loading 초기값이 true, 재로드 시엔 reload()가 loading을 켠다. 언마운트/재로드 시 abort 정리.
  useEffect(() => {
    const ctrl = new AbortController()
    void (async () => {
      try {
        const [s, c] = await Promise.all([getSettings(ctrl.signal), getCatalog(ctrl.signal)])
        if (ctrl.signal.aborted) return
        setSettings(s)
        setCatalog(c)
        setError(null)
      } catch (e) {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '설정을 불러오지 못했어요')
      } finally {
        if (!ctrl.signal.aborted) setLoading(false)
      }
    })()
    return () => ctrl.abort()
  }, [reloadKey])

  const startReindexPoll = useCallback(() => {
    stopPoll()
    let attempts = 0
    pollTimer.current = setInterval(() => {
      attempts += 1
      getSettings()
        .then((s) => {
          if (!mounted.current) return
          setSettings(s)
          if (s.embedding.status !== 'REINDEXING' || attempts >= POLL_MAX_ATTEMPTS) stopPoll()
        })
        .catch(() => {
          // 폴링 실패는 배지 갱신만 멈춘다(다음 저장/새로고침에서 회복). 무한 시도 방지.
          if (attempts >= POLL_MAX_ATTEMPTS) stopPoll()
        })
    }, POLL_INTERVAL_MS)
  }, [stopPoll])

  const save = useCallback(
    async (body: SettingsUpdateRequest) => {
      const updated = await updateSettings(body)
      if (!mounted.current) return updated
      setSettings(updated)
      if (updated.embedding.status === 'REINDEXING') startReindexPoll()
      else stopPoll()
      return updated
    },
    [startReindexPoll, stopPoll]
  )

  // 이벤트 핸들러(재시도 버튼)에서만 호출 → 즉시 loading 표시 OK.
  const reload = useCallback(() => {
    stopPoll()
    setLoading(true)
    setError(null)
    setReloadKey((k) => k + 1)
  }, [stopPoll])

  return { settings, catalog, loading, error, reload, save }
}
