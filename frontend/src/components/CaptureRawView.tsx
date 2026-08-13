import { useEffect, useRef, useState } from 'react'
import { getCaptureRaw } from '../api/client'
import type { CaptureRawResponse } from '../api/dto'

interface CaptureRawViewProps {
  captureId: string | number
}

type LoadState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; data: CaptureRawResponse }

/**
 * 원본 보기(Unit Q2) — 마스킹된 원문 캡처를 접힘 섹션으로 보여준다. 기본 접힘.
 * 첫 확장 시에만 GET /api/captures/{id}를 지연 조회하고 결과를 캐시(재확장 시 재조회 안 함).
 * 조용한 실패 금지: 로딩/에러 상태를 항상 노출하고, 에러엔 재시도 버튼을 둔다.
 *
 * 호출부는 `key={captureId}`로 렌더해야 한다 — captureId가 바뀌면 리마운트되어 캐시가
 * 자연히 무효화된다(React 권장: prop 변경 시 전체 상태 초기화 = key로 리마운트).
 */
export function CaptureRawView({ captureId }: CaptureRawViewProps) {
  const [open, setOpen] = useState(false)
  const [state, setState] = useState<LoadState>({ kind: 'idle' })
  const ctrlRef = useRef<AbortController | null>(null)

  // 언마운트 시 진행 중인 fetch 중단.
  useEffect(() => {
    return () => ctrlRef.current?.abort()
  }, [])

  const load = () => {
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    setState({ kind: 'loading' })
    void (async () => {
      try {
        const data = await getCaptureRaw(captureId, ctrl.signal)
        if (ctrl.signal.aborted) return
        setState({ kind: 'ready', data })
      } catch (e) {
        if (ctrl.signal.aborted) return
        const message = e instanceof Error ? e.message : '알 수 없는 오류'
        setState({ kind: 'error', message })
      }
    })()
  }

  const toggle = () => {
    setOpen((o) => !o)
    if (state.kind === 'idle') load() // 첫 확장에만 지연 조회 — 이후엔 캐시된 state 재사용.
  }

  return (
    <div style={{ marginTop: 18, paddingTop: 14, borderTop: '1px solid var(--border)' }}>
      <button type="button" className="chipbtn" onClick={toggle}>
        {open ? '원본 접기 ▲' : '원본 보기 ▼'}
      </button>

      {open && (
        <div style={{ marginTop: 10 }}>
          {state.kind === 'loading' && (
            <div className="v" style={{ color: 'var(--text-faint)' }}>
              불러오는 중…
            </div>
          )}

          {state.kind === 'error' && (
            <div className="v" style={{ color: 'var(--bad)' }}>
              원본을 불러오지 못했어요: {state.message}
              <div style={{ marginTop: 8 }}>
                <button type="button" className="btn sm" onClick={load}>
                  다시 시도
                </button>
              </div>
            </div>
          )}

          {state.kind === 'ready' && (
            <div>
              <div className="kv">
                <div className="k">출처</div>
                <div className="v">{state.data.sourceType}</div>
                <div className="k">저장일</div>
                <div className="v">{state.data.createdAt.slice(0, 10)}</div>
              </div>
              <div
                className="v"
                style={{
                  whiteSpace: 'pre-wrap',
                  padding: 12,
                  background: 'var(--surface-2)',
                  borderRadius: 8,
                  lineHeight: 1.6,
                }}
              >
                {state.data.rawText}
              </div>
              <div className="note" style={{ marginTop: 10 }}>
                <b>안내</b>
                <span>비밀값은 가려진 원문이에요.</span>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
