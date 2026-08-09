import { useNavigate, useParams } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'
import { RecurTimeline } from '../components/RecurTimeline'

export function MemoryDetailPage() {
  const { id } = useParams()
  const { getMemory, markRecur, resolveNow, cycleStatus, archiveMemory } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()

  const m = id ? getMemory(id) : undefined
  if (!m) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/memories')}>
          ← 내 기억으로
        </button>
        <div className="card empty">없는 기억이에요.</div>
      </section>
    )
  }

  const meta = TYPE_META[m.type]
  const isRecur = m.hits > 1

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
          {m.type === 'ts' && <StatusPill status={m.ts.status} />}
        </div>
        <div style={{ fontWeight: 700, fontSize: 19, letterSpacing: '-.01em' }}>{m.title}</div>
        <div className="eyebrow" style={{ marginTop: 6 }}>
          {m.created} 저장
        </div>

        {isRecur && (
          <>
            <span className="recur" style={{ marginTop: 10 }}>
              🔁 지금까지 {m.hits}번 마주친 문제
            </span>
            <RecurTimeline memory={m} />
          </>
        )}

        {m.type === 'ts' ? (
          <div className="kv">
            <div className="k">문제</div>
            <div className="v">{m.ts.problem}</div>
            {m.ts.tried && (
              <>
                <div className="k">시도한 것</div>
                <div className="v">{m.ts.tried}</div>
              </>
            )}
            <div className="k">해결책</div>
            <div className="v hi">{m.ts.solution || '(아직 없음)'}</div>
            <div className="k">상태</div>
            <div className="v">
              <StatusPill status={m.ts.status} />
            </div>
          </div>
        ) : (
          <div className="kv">
            <div className="k">내용</div>
            <div className="v">{m.kn.content}</div>
          </div>
        )}

        <button className="evidence" onClick={() => navigate(`/source/${m.captureId}`)}>
          📎 근거: 원본 대화 보기
        </button>

        <hr className="divider" />
        <div className="row">
          {m.type === 'ts' && m.ts.status !== '해결' && (
            <button
              className="btn primary"
              onClick={() => {
                resolveNow(m.id)
                toast('✓ 이번엔 완전히 해결 — 상태 갱신 + 재발 기록')
              }}
            >
              ✓ 이번엔 해결됨
            </button>
          )}
          <button
            className="btn"
            onClick={() => {
              markRecur(m.id)
              toast(`🔁 ${m.hits + 1}회째 마주침으로 기록 · 예전 해결책을 바로 꺼냈어요`)
            }}
          >
            🔁 또 겪었어요
          </button>
          {m.type === 'ts' && (
            <button
              className="btn"
              onClick={() => {
                cycleStatus(m.id)
                toast('상태 변경됨')
              }}
            >
              상태 변경
            </button>
          )}
          <button className="btn" onClick={() => toast('편집 모드 (목업)')}>
            ✎ 수정
          </button>
          <button
            className="btn danger"
            onClick={() => {
              archiveMemory(m.id)
              toast('보관됨 · 삭제 아니라 상태만 바뀜 (복구 가능)')
              navigate('/memories')
            }}
          >
            보관
          </button>
        </div>
        <div className="note">
          <b>설계</b>
          <span>
            같은 문제 재발은 새 기억이 아니라 <b>기존 기억의 재발 카운트</b>로 누적 — 자주 겪는
            문제가 위로. 보관은 삭제가 아니라 상태 변경(하드 삭제 없음).
          </span>
        </div>
      </div>
    </section>
  )
}
