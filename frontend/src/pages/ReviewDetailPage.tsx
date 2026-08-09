import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import type { MemoryTypeKey, ReviewCard } from '../types'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { findSimilarMemory } from '../lib/search'
import { TYPE_KEYS, TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'

function blankCard(): ReviewCard {
  return {
    type: 'kn',
    title: '',
    ts: { problem: '', tried: '', solution: '', status: '미해결' },
    kn: { content: '' },
  }
}

export function ReviewDetailPage() {
  const { id } = useParams()
  const { getReview, memories, approveReview, rejectReview, markRecur } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()

  const review = id ? getReview(id) : undefined
  const [cards, setCards] = useState<ReviewCard[]>(() =>
    review ? review.cards.map((c) => ({ ...c, ts: { ...c.ts }, kn: { ...c.kn } })) : []
  )

  if (!review) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => navigate('/reviews')}>
          ← 검토함으로
        </button>
        <div className="card empty">이미 처리된 항목이에요.</div>
      </section>
    )
  }

  const patchCard = (ci: number, mutate: (c: ReviewCard) => ReviewCard) =>
    setCards((prev) => prev.map((c, i) => (i === ci ? mutate(c) : c)))

  const setType = (ci: number, type: MemoryTypeKey) => patchCard(ci, (c) => ({ ...c, type }))
  const setTitle = (ci: number, title: string) => patchCard(ci, (c) => ({ ...c, title }))
  const setTsField = (ci: number, field: 'problem' | 'tried' | 'solution', val: string) =>
    patchCard(ci, (c) => ({ ...c, ts: { ...c.ts, [field]: val } }))
  const setKnContent = (ci: number, content: string) =>
    patchCard(ci, (c) => ({ ...c, kn: { ...c.kn, content } }))

  const addCard = () => {
    setCards((prev) => [...prev, blankCard()])
    toast('카드 추가 — 유형을 골라 채우세요')
  }
  const excludeCard = (ci: number) => {
    if (cards.length <= 1) {
      toast('마지막 카드는 제외할 수 없어요 (반려를 쓰세요)')
      return
    }
    setCards((prev) => prev.filter((_, i) => i !== ci))
  }

  const recordRecur = (ci: number, memId: string, hits: number) => {
    markRecur(memId)
    if (cards.length <= 1) {
      rejectReview(review.id)
      toast(`🔁 ${hits + 1}회째 재발로 기록 · 중복 기억을 안 만들었어요`)
      navigate(`/memories/${memId}`)
    } else {
      excludeCard(ci)
      toast('🔁 재발로 기록 · 이 카드는 검토에서 제외했어요')
    }
  }

  const approve = () => {
    const n = approveReview(review.id, cards)
    toast(`✓ 기억 ${n}건 저장됨 (원본 1개 ← 기억 ${n})`)
    navigate('/memories')
  }
  const reject = () => {
    rejectReview(review.id)
    toast('반려됨 · 원본은 보존돼요')
    navigate('/reviews')
  }

  return (
    <section className="screen">
      <button className="backbtn" onClick={() => navigate('/reviews')}>
        ← 검토함으로
      </button>
      <div className="eyebrow">검토함 · 승인 게이트</div>
      <h1 className="h1">이 원본에서 기억 만들기</h1>
      <p className="lede">
        <b>유형은 직접 고르고</b>, 한 세션에 여러 주제가 있으면 <b>카드를 나눠</b> 저장하세요.
        승인해야 기억에 들어가요.
      </p>
      <button
        className="evidence"
        style={{ marginBottom: 16 }}
        onClick={() => navigate(`/source/${review.captureId}`)}
      >
        📎 원본 세션 보기 (마스킹됨)
      </button>

      {cards.map((c, ci) => {
        const dup = findSimilarMemory(memories, c)
        return (
          <div className="card pad memcard" style={{ marginBottom: 14 }} key={ci}>
            <div className="cardnum">
              기억 카드 {ci + 1}
              {ci > 0 ? ' · 같은 원본에서' : ''}
            </div>

            {dup && (
              <div className="recur-banner" style={{ marginBottom: 14 }}>
                <span>🔁</span>
                <span>
                  이미 기억에 있어요: <b>{dup.title}</b> ({dup.hits || 1}회). 새로 만들지 말고{' '}
                  <b>재발로 기록</b>하면 한 기억에 쌓여요.
                  <button
                    className="btn sm"
                    style={{ marginLeft: 6 }}
                    onClick={() => recordRecur(ci, dup.id, dup.hits || 1)}
                  >
                    🔁 재발로 기록
                  </button>
                </span>
              </div>
            )}

            <div className="between" style={{ marginBottom: 14 }}>
              <div className="typesel">
                {TYPE_KEYS.map((t) => (
                  <button
                    key={t}
                    className={c.type === t ? 'tsel on' : 'tsel'}
                    onClick={() => setType(ci, t)}
                  >
                    {TYPE_META[t].label}
                  </button>
                ))}
              </div>
              <button className="btn ghost sm" onClick={() => excludeCard(ci)}>
                ✕ 제외
              </button>
            </div>

            <input
              type="text"
              value={c.title}
              style={{ fontWeight: 600, marginBottom: 12 }}
              onChange={(e) => setTitle(ci, e.target.value)}
            />

            {c.type === 'ts' ? (
              <div className="kv">
                <div className="k">문제</div>
                <div className="v">
                  <input
                    type="text"
                    value={c.ts.problem}
                    onChange={(e) => setTsField(ci, 'problem', e.target.value)}
                  />
                </div>
                <div className="k">시도</div>
                <div className="v">
                  <input
                    type="text"
                    value={c.ts.tried}
                    onChange={(e) => setTsField(ci, 'tried', e.target.value)}
                  />
                </div>
                <div className="k">해결</div>
                <div className="v">
                  <input
                    type="text"
                    value={c.ts.solution}
                    onChange={(e) => setTsField(ci, 'solution', e.target.value)}
                  />
                </div>
                <div className="k">상태</div>
                <div className="v">
                  <StatusPill status={c.ts.status} />
                </div>
              </div>
            ) : (
              <div className="kv">
                <div className="k">내용</div>
                <div className="v">
                  <textarea
                    rows={3}
                    value={c.kn.content}
                    onChange={(e) => setKnContent(ci, e.target.value)}
                  />
                </div>
              </div>
            )}
          </div>
        )
      })}

      <button
        className="btn"
        style={{ width: '100%', justifyContent: 'center', marginBottom: 16 }}
        onClick={addCard}
      >
        ＋ 이 원본에서 기억 나누기 (다른 주제 카드 추가)
      </button>

      <div className="row">
        <button className="btn primary" onClick={approve}>
          ✓ 모두 승인하고 저장
        </button>
        <button className="btn danger" onClick={reject}>
          ✕ 전체 반려
        </button>
      </div>
      <div className="note">
        <b>설계</b>
        <span>
          유형은 사람이 선택(수동) · 섞인 세션은 카드로 쪼개 각기 저장 · 원본 1개 ← 기억 여러
          개(1:N).
        </span>
      </div>
    </section>
  )
}
