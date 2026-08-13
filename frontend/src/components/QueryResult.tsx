import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { AnswerFragment } from '../api/dto'
import { getMemoryDetail, streamQuery } from '../api/client'

/**
 * 물어보기 결과 — POST /api/query 를 SSE로 스트리밍 소비한다.
 * 답변은 저장된 근거(memoryId)에 매인다. 조각이 하나도 없으면 "기록 없음"으로 표시(근거 없는 생성 금지).
 */
export function QueryResult({ question, onBack }: { question: string; onBack: () => void }) {
  const navigate = useNavigate()
  const [fragments, setFragments] = useState<AnswerFragment[]>([])
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 근거 제목: memoryId → title. 전역 목록을 통째로 싣지 않고 근거 id만 개별 조회한다(근거는 보통 소수).
  const [titles, setTitles] = useState<Record<number, string>>({})
  const requestedRef = useRef<Set<number>>(new Set())

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

  // 근거: 조각이 참조한 memoryId(중복 제거, 등장 순서 유지).
  const citedIds = useMemo(() => {
    const ids: number[] = []
    for (const f of fragments) {
      if (f.memoryId != null && !ids.includes(f.memoryId)) ids.push(f.memoryId)
    }
    return ids
  }, [fragments])

  // 근거 제목을 id별로 조회(이미 요청한 id는 ref로 건너뛰어 중복 요청 방지). 실패해도 폴백 라벨로 노출.
  useEffect(() => {
    const ctrl = new AbortController()
    for (const id of citedIds) {
      if (requestedRef.current.has(id)) continue
      requestedRef.current.add(id)
      getMemoryDetail(id, ctrl.signal)
        .then((d) => setTitles((prev) => ({ ...prev, [id]: d.title })))
        .catch(() => {
          if (ctrl.signal.aborted) return
          setTitles((prev) => ({ ...prev, [id]: `기억 #${id}` }))
        })
    }
    return () => ctrl.abort()
  }, [citedIds])

  const citations = citedIds.map((id) => ({ id, title: titles[id] ?? `기억 #${id}` }))

  const isEmpty = done && !error && answer.trim().length === 0

  return (
    <section className="screen">
      <button className="backbtn" onClick={onBack}>
        ← 다시 묻기
      </button>

      {error ? (
        <div className="miss-banner">
          <span className="mk">!</span> 답변 중 문제가 생겼어요: {error}
        </div>
      ) : isEmpty ? (
        <div className="miss-banner">
          <span className="mk">§</span> 기억엔 없는 질문이에요. 지어내지 않고 "기록 없음"으로
          남겨요.
        </div>
      ) : (
        <div className="qstatus">
          <span className="d" /> {done ? '내 기억에서 찾은 답이에요' : '기억을 뒤지는 중…'}
        </div>
      )}

      {!isEmpty && !error && (
        <div className="card pad">
          <div className="qecho">{question}</div>
          <div className="v" style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
            {answer}
            {!done && <span className="cursor" aria-hidden="true" />}
          </div>

          {citations.length > 0 && (
            <>
              <div className="provline">
                <span className="mk">§</span> 근거 {citations.length}건 — 아래 기억에 매인 답변
              </div>
              {citations.map((c, i) => (
                <button
                  key={c.id}
                  className="srcitem"
                  style={{ width: '100%', textAlign: 'left' }}
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
