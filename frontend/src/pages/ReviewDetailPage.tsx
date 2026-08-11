import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'

export function ReviewDetailPage() {
  const { id } = useParams()
  const { getReview, approveReview, rejectReview } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  const review = id ? getReview(id) : undefined

  if (!review) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/reviews')}>
          ← 검토함으로
        </button>
        <div className="card empty">이미 처리됐거나 없는 항목이에요.</div>
      </section>
    )
  }

  const approve = async () => {
    setBusy(true)
    try {
      const n = await approveReview(review.id)
      toast(`✓ 기억으로 저장됨 (#${n})`)
      navigate('/memories')
    } catch (e) {
      toast(`⚠️ 승인 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
      setBusy(false)
    }
  }
  const reject = async () => {
    setBusy(true)
    try {
      await rejectReview(review.id)
      toast('반려됨 · 원본은 보존돼요')
      navigate('/reviews')
    } catch (e) {
      toast(`⚠️ 반려 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
      setBusy(false)
    }
  }

  return (
    <section className="screen">
      <button className="backbtn" onClick={() => navigate('/reviews')}>
        ← 검토함으로
      </button>
      <div className="eyebrow">검토함 · 승인 게이트</div>
      <h1 className="h1">이 원본에서 만든 기억</h1>
      <p className="lede">
        AI가 뽑은 내용이에요. 확인하고 <b>승인</b>하면 내 기억에 저장돼요. 아니면 <b>반려</b>하세요.
      </p>

      {review.cards.map((c, ci) => {
        const meta = TYPE_META[c.type]
        return (
          <div className="card pad memcard" style={{ marginBottom: 14 }} key={ci}>
            <div className="between" style={{ marginBottom: 10 }}>
              <span className="type-tag">
                <span className="dot" style={{ background: `var(${meta.varc})` }} />
                {meta.short}
              </span>
            </div>
            <div style={{ fontWeight: 700, fontSize: 18, marginBottom: 12 }}>{c.title}</div>
            {c.type === 'ts' ? (
              <div className="kv">
                <div className="k">문제</div>
                <div className="v">{c.ts.problem}</div>
                <div className="k">시도</div>
                <div className="v">{c.ts.tried || '(없음)'}</div>
                <div className="k">해결</div>
                <div className="v hi">{c.ts.solution || '(아직 없음)'}</div>
                <div className="k">상태</div>
                <div className="v">
                  <StatusPill status={c.ts.status} />
                </div>
              </div>
            ) : (
              <div className="kv">
                <div className="k">내용</div>
                <div className="v">{c.kn.content}</div>
              </div>
            )}
          </div>
        )
      })}

      <div className="row">
        <button className="btn primary" onClick={() => void approve()} disabled={busy}>
          ✓ 승인하고 저장
        </button>
        <button className="btn danger" onClick={() => void reject()} disabled={busy}>
          ✕ 반려
        </button>
      </div>
      <div className="note">
        <b>설계</b>
        <span>승인해야 기억에 들어가요(승인 게이트) · 반려해도 원본은 지우지 않고 보존.</span>
      </div>
    </section>
  )
}
