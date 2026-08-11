import { useNavigate, useParams } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'

export function MemoryDetailPage() {
  const { id } = useParams()
  const { getMemory } = useRecall()
  const navigate = useNavigate()

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
      </div>
    </section>
  )
}
