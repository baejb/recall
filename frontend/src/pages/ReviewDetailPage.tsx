import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { TYPE_META } from '../lib/typeMeta'
import { JUDGEMENT_META } from '../lib/judgement'
import { KnowledgeCardView } from '../components/KnowledgeCardView'
import { TroubleshootingCardView } from '../components/TroubleshootingCardView'
import { CaptureRawView } from '../components/CaptureRawView'

export function ReviewDetailPage() {
  const { id } = useParams()
  const { getReview, approveReview, rejectReview, loading, error } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  const review = id ? getReview(id) : undefined

  // 로딩·서버장애를 "없는 항목"으로 오인 표시하지 않는다(조용한 실패 금지). 직접 진입·새로고침 시
  // fetchAll 이 끝나기 전엔 review 가 undefined 라, 그 구간을 로딩/에러와 구분해야 한다.
  if (!review && loading && !error) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/reviews')}>
          ← 검토함으로
        </button>
        <div className="card pad">불러오는 중…</div>
      </section>
    )
  }

  if (!review && error) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/reviews')}>
          ← 검토함으로
        </button>
        <div className="card empty">불러오지 못했어요: {error}</div>
      </section>
    )
  }

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

  const judgeMeta = JUDGEMENT_META[review.judgement]

  const approve = async () => {
    setBusy(true)
    try {
      const n = await approveReview(review.id)
      toast(`기억으로 저장됨 (#${n})`)
      navigate('/memories')
    } catch (e) {
      toast(`승인 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
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
      toast(`반려 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
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

      {/*
        기존 기억과의 관계(S4 판정)를 승인 버튼보다 위에 둔다. 백엔드는 충돌을 자동으로 덮어쓰지 않고
        사람에게 넘기는데(불변 원칙 3), 그 사실이 화면에 없으면 검토자는 모순을 모른 채 승인한다 —
        게이트가 형식만 남는다.
      */}
      <div className="card pad" style={{ marginBottom: 14 }}>
        <div className="between">
          <b>기존 기억과의 관계</b>
          <span className={`pill ${judgeMeta.tone}`}>{judgeMeta.label}</span>
        </div>
        <div className={judgeMeta.tone === 'bad' ? 'note bad' : 'note'}>
          <b>안내</b>
          <span>{judgeMeta.hint}</span>
        </div>
        {review.judgeReason && (
          <div className="note">
            <b>판정 근거</b>
            <span>{review.judgeReason}</span>
          </div>
        )}
        {review.targetMemoryId && (
          <button
            className="btn sm"
            style={{ marginTop: 12 }}
            onClick={() => navigate(`/memories/${review.targetMemoryId}`)}
          >
            관련 기억 #{review.targetMemoryId} 열기 ›
          </button>
        )}
      </div>

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
              <TroubleshootingCardView fields={c.ts} />
            ) : (
              <KnowledgeCardView
                summary={c.kn.summary}
                facts={c.kn.facts}
                keywords={c.kn.keywords}
                document={c.kn.content}
              />
            )}
          </div>
        )
      })}

      <CaptureRawView key={review.captureId} captureId={review.captureId} />

      <div className="row" style={{ marginTop: 18 }}>
        <button className="btn primary" onClick={() => void approve()} disabled={busy}>
          승인하고 저장
        </button>
        <button className="btn danger" onClick={() => void reject()} disabled={busy}>
          반려
        </button>
      </div>
      <div className="note">
        <b>설계</b>
        <span>승인해야 기억에 들어가요(승인 게이트) · 반려해도 원본은 지우지 않고 보존.</span>
      </div>
    </section>
  )
}
