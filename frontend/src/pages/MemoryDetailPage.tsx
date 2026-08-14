import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { getMemoryDetail, updateMemoryStatus, type MemoryStatus } from '../api/client'
import { toTypeKey } from '../api/adapter'
import type { MemoryDetailResponse } from '../api/dto'
import { useToast } from '../hooks/useToast'
import { TYPE_META } from '../lib/typeMeta'
import { KnowledgeCardView } from '../components/KnowledgeCardView'
import { CaptureRawView } from '../components/CaptureRawView'

const STATUS_TOAST: Record<MemoryStatus, string> = {
  active: '복원했어요',
  archived: '숨겼어요 — 상태 탭에서 되돌릴 수 있어요',
  incorrect: '폐기로 표시했어요 — 기록은 보존돼요',
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'notfound' }
  | { kind: 'ready'; detail: MemoryDetailResponse }

/** 기억 상세 — GET /api/memories/{id} 단건 조회. 조용한 실패 금지: 로딩/에러/404를 항상 노출. */
export function MemoryDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [retryTick, setRetryTick] = useState(0)
  const [busy, setBusy] = useState(false)

  // 상태 전이(삭제 대신 상태 보존). 성공 시 반환된 상세로 갱신 → 버튼·배지가 새 상태를 반영한다.
  const changeStatus = async (next: MemoryStatus) => {
    if (!id) return
    setBusy(true)
    try {
      const updated = await updateMemoryStatus(id, next)
      setState({ kind: 'ready', detail: updated })
      toast(STATUS_TOAST[next])
    } catch (e) {
      toast(e instanceof Error ? e.message : '상태 변경에 실패했어요')
    } finally {
      setBusy(false)
    }
  }

  // 이펙트 본문에서 동기 setState를 피해 async IIFE로 감싼다(await 이후에만 setState).
  // loading 초기값은 useState로, 재시도는 아래 retry()가 이벤트 핸들러에서 loading을 켠 뒤 재실행시킨다.
  useEffect(() => {
    if (!id) return // id 없음은 아래 렌더 분기에서 직접 처리
    const ctrl = new AbortController()
    void (async () => {
      try {
        const detail = await getMemoryDetail(id, ctrl.signal)
        if (ctrl.signal.aborted) return
        setState({ kind: 'ready', detail })
      } catch (e) {
        if (ctrl.signal.aborted) return
        const message = e instanceof Error ? e.message : '알 수 없는 오류'
        // client.ts request()의 에러 메시지 포맷: "GET /api/memories/{id} → {status} {detail}"
        if (message.includes('→ 404')) setState({ kind: 'notfound' })
        else setState({ kind: 'error', message })
      }
    })()
    return () => ctrl.abort()
  }, [id, retryTick])

  // 이벤트 핸들러(재시도 버튼)에서만 호출 → 즉시 loading 표시 OK.
  const retry = () => {
    setState({ kind: 'loading' })
    setRetryTick((t) => t + 1)
  }

  if (!id) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/memories')}>
          ← 내 기억으로
        </button>
        <div className="card empty">없는 기억이에요.</div>
      </section>
    )
  }

  if (state.kind === 'loading') {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/memories')}>
          ← 내 기억으로
        </button>
        <div className="card empty">불러오는 중…</div>
      </section>
    )
  }

  if (state.kind === 'notfound') {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/memories')}>
          ← 내 기억으로
        </button>
        <div className="card empty">없는 기억이에요.</div>
      </section>
    )
  }

  if (state.kind === 'error') {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/memories')}>
          ← 내 기억으로
        </button>
        <div className="card empty">
          불러오지 못했어요: {state.message}
          <div style={{ marginTop: 10 }}>
            <button className="btn" onClick={retry}>
              다시 시도
            </button>
          </div>
        </div>
      </section>
    )
  }

  const d = state.detail
  const meta = TYPE_META[toTypeKey(d.type)]

  return (
    <section className="screen">
      <button className="backbtn" onClick={() => navigate('/memories')}>
        ← 내 기억으로
      </button>
      <div className="card pad">
        <div className="between" style={{ marginBottom: 6 }}>
          <span className="type-tag">
            <span className="dot" style={{ background: `var(${meta.varc})` }} />
            {meta.short}
          </span>
          {d.status !== 'active' && (
            <span className={`pill ${d.status === 'archived' ? 'warn' : 'bad'}`}>
              {d.status === 'archived' ? '숨김' : '폐기'}
            </span>
          )}
        </div>
        <div style={{ fontWeight: 700, fontSize: 19, letterSpacing: '-.01em' }}>{d.title}</div>
        <div className="eyebrow" style={{ marginTop: 6 }}>
          {d.createdAt.slice(0, 10)} 저장
        </div>

        <div style={{ marginTop: 14 }}>
          <KnowledgeCardView
            summary={d.summary}
            facts={d.facts}
            keywords={d.keywords}
            document={d.document}
          />
        </div>

        <CaptureRawView key={d.captureId} captureId={d.captureId} />

        <hr className="divider" />
        <div className="row">
          {d.status !== 'active' && (
            <button
              className="btn primary"
              disabled={busy}
              onClick={() => void changeStatus('active')}
            >
              원복
            </button>
          )}
          {d.status === 'active' && (
            <button className="btn" disabled={busy} onClick={() => void changeStatus('archived')}>
              숨기기
            </button>
          )}
          {d.status !== 'incorrect' && (
            <button
              className="btn danger"
              disabled={busy}
              onClick={() => void changeStatus('incorrect')}
            >
              폐기
            </button>
          )}
        </div>
        <p className="lede" style={{ fontSize: 12, marginTop: 8 }}>
          삭제가 아니라 상태를 바꿔요 — 숨기거나 폐기해도 기록은 보존되고 언제든 복원할 수 있어요.
        </p>
      </div>
    </section>
  )
}
