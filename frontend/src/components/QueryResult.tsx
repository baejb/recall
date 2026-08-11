import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import type { AnswerFragment } from '../api/dto'
import { streamQuery } from '../api/client'

/**
 * 물어보기 결과 — POST /api/query 를 SSE로 스트리밍 소비한다.
 * 답변은 저장된 근거(memoryId)에 매인다. 조각이 하나도 없으면 "기록 없음"으로 표시(근거 없는 생성 금지).
 */
export function QueryResult({ question, onBack }: { question: string; onBack: () => void }) {
  const { memories } = useRecall()
  const navigate = useNavigate()
  const [fragments, setFragments] = useState<AnswerFragment[]>([])
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // question별로 QueryPage가 key로 재마운트하므로 상태 리셋 불필요.
  // 이펙트 본문에서 동기 setState를 피하려 setState는 스트림 콜백/후속에서만 호출한다.
  useEffect(() => {
    const ctrl = new AbortController()
    streamQuery(question, (f) => setFragments((prev) => [...prev, f]), ctrl.signal)
      .then(() => setDone(true))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return // 언마운트/재질문에 의한 중단은 에러 아님
        setError(e instanceof Error ? e.message : '답변을 받지 못했어요')
        setDone(true)
      })
    return () => ctrl.abort()
  }, [question])

  const answer = useMemo(() => fragments.map((f) => f.text).join(''), [fragments])

  // 근거: 조각이 참조한 memoryId(중복 제거). 저장된 기억에서 제목을 찾아 링크.
  const citations = useMemo(() => {
    const ids: number[] = []
    for (const f of fragments) {
      if (f.memoryId != null && !ids.includes(f.memoryId)) ids.push(f.memoryId)
    }
    return ids.map((mid) => ({
      id: mid,
      title: memories.find((m) => m.id === String(mid))?.title ?? `기억 #${mid}`,
    }))
  }, [fragments, memories])

  const isEmpty = done && !error && answer.trim().length === 0

  return (
    <section className="screen">
      <button className="backbtn" onClick={onBack}>
        ← 다시 묻기
      </button>

      {error ? (
        <div className="miss-banner">
          <span>⚠️</span> 답변 중 문제가 생겼어요: {error}
        </div>
      ) : isEmpty ? (
        <div className="miss-banner">
          <span>🔍</span> 기억엔 없는 질문이에요. 지어내지 않고 "기록 없음"으로 남겨요.
        </div>
      ) : (
        <div className="found-banner">
          <span>🧠</span> {done ? '내 기억에서 찾은 답이에요.' : '기억을 뒤지는 중…'}
        </div>
      )}

      {!isEmpty && !error && (
        <div className="card pad">
          <div style={{ fontWeight: 700, fontSize: 16, marginBottom: 10 }}>{question}</div>
          <div className="v" style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
            {answer}
            {!done && <span className="cursor">▍</span>}
          </div>

          {citations.length > 0 && (
            <>
              <hr className="divider" />
              <h3
                style={{
                  fontSize: 11.5,
                  color: 'var(--text-faint)',
                  fontFamily: 'var(--font-mono)',
                  margin: '0 0 8px',
                  textTransform: 'uppercase',
                  letterSpacing: '.08em',
                }}
              >
                근거 ({citations.length})
              </h3>
              {citations.map((c, i) => (
                <button
                  key={c.id}
                  className="srcitem"
                  style={{ width: '100%', textAlign: 'left', marginBottom: 8 }}
                  onClick={() => navigate(`/memories/${c.id}`)}
                >
                  <div className="snum">{i + 1}</div>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13.5 }}>{c.title}</div>
                    <div style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>기억 보기 →</div>
                  </div>
                </button>
              ))}
            </>
          )}
        </div>
      )}

      <div className="note">
        <b>설계</b>
        <span>답변은 저장된 근거(memoryId)에 매여요. 근거가 없으면 지어내지 않고 "기록 없음".</span>
      </div>
    </section>
  )
}
