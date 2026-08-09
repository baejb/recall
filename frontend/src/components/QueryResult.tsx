import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { QueryScope } from '../types'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { matchReason, memSummary, searchMemories } from '../lib/search'
import { StatusPill } from './StatusPill'
import { TypeTag } from './TypeTag'
import { RecurBadge } from './RecurBadge'
import { RecurTimeline } from './RecurTimeline'

const FLASH_MS = 1200

/** 물어보기 결과 — 기억에서 찾으면 근거(citation)와 함께, 없으면 "기록 없음"으로 표시. */
export function QueryResult({
  question,
  scope,
  onBack,
}: {
  question: string
  scope: QueryScope
  onBack: () => void
}) {
  const { memories, getCapture, recordRecurFromQuery } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()
  const srcRef = useRef<HTMLDivElement>(null)
  const [flash, setFlash] = useState(false)

  const hits = useMemo(() => searchMemories(memories, question, scope), [memories, question, scope])

  const jumpToSource = () => {
    srcRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setFlash(true)
    window.setTimeout(() => setFlash(false), FLASH_MS)
  }

  if (!hits.length) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={onBack}>
          ← 다시 묻기
        </button>
        <div className="miss-banner">
          <span>🔍</span> 기억엔 없는 질문이에요. 지어내지 않고, 지금 새로 풀어드릴게요.
        </div>
        <div className="card pad">
          <div className="between" style={{ marginBottom: 10 }}>
            <span className="qtype">🆕 새 질문</span>
            <span className="eyebrow">저장된 기억 아님</span>
          </div>
          <div style={{ fontWeight: 700, fontSize: 16, marginBottom: 6 }}>{question}</div>
          <p style={{ margin: '0 0 8px', fontSize: 14.5, color: 'var(--text-muted)' }}>
            (LLM이 새로 생성한 답변이 여기 표시돼요. 이건 저장된 기억이 아니에요.)
          </p>
          <hr className="divider" />
          <div className="between">
            <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>
              이 답변, 기억으로 저장할까요?
            </span>
            <button className="btn primary" onClick={() => navigate('/capture')}>
              💾 저장하러 가기 →
            </button>
          </div>
        </div>
        <div className="note">
          <b>설계</b>
          <span>
            "없음"이 막다른 길이 아니라 새 지식이 됨 — 새로 풀고 → 저장 제안 → 다음엔 기억함.
          </span>
        </div>
      </section>
    )
  }

  const top = hits[0]
  const reason = matchReason(top, question)
  const isRecur = (top.hits || 1) > 1
  const qlabel = top.type === 'ts' ? '🔧 트러블슈팅 · 해결 회상형' : '📘 지식 · 개념 질문'
  const cap = getCapture(top.captureId)
  const related = hits.slice(1, 4)

  return (
    <section className="screen">
      <button className="backbtn" onClick={onBack}>
        ← 다시 묻기
      </button>

      {isRecur ? (
        <div className="recur-banner">
          <span>🔁</span>
          <span>
            이거, 예전에도 겪었어요 — <b>{top.hits}번째</b> 마주침이에요. 지난 {top.lastSeen}에
            이렇게 풀었어요.
          </span>
        </div>
      ) : (
        <div className="found-banner">
          <span>🧠</span> 예전 기억에서 찾았어요.
        </div>
      )}

      <div className="card pad">
        <div className="between" style={{ marginBottom: 8 }}>
          <span style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="qtype">{qlabel}</span>
            <span className={`matchreason ${reason.cls}`}>{reason.label}</span>
          </span>
          {top.type === 'ts' && <StatusPill status={top.ts.status} />}
        </div>
        <div style={{ fontWeight: 700, fontSize: 17 }}>{top.title}</div>

        {isRecur && <RecurTimeline memory={top} />}

        {top.type === 'ts' ? (
          <div className="kv">
            <div className="k">해결책</div>
            <div className="v hi">
              {top.ts.solution || top.ts.problem}{' '}
              <button className="cite" onClick={jumpToSource}>
                1
              </button>
            </div>
          </div>
        ) : (
          <div className="v" style={{ margin: '10px 0' }}>
            {top.kn.content}{' '}
            <button className="cite" onClick={jumpToSource}>
              1
            </button>
          </div>
        )}

        <div className="between" style={{ marginTop: 2 }}>
          <button className="btn ghost sm" onClick={() => navigate(`/memories/${top.id}`)}>
            전체 맥락 보기 →
          </button>
          <div className="row">
            <button
              className="btn ghost sm"
              onClick={() => {
                recordRecurFromQuery(top.id)
                toast('🔁 재발로 기록됨')
              }}
            >
              🔁 또 겪었어요 (재발 기록)
            </button>
            <button
              className="btn ghost sm"
              onClick={() => toast('현재 맥락으로 새 답변 생성 (목업)')}
            >
              🆕 이 말고 새로 풀기
            </button>
          </div>
        </div>

        <hr className="divider" />
        <h3
          style={{
            fontSize: 11.5,
            color: 'var(--text-faint)',
            fontFamily: 'var(--font-mono)',
            margin: '0 0 4px',
            textTransform: 'uppercase',
            letterSpacing: '.08em',
          }}
        >
          근거 (1)
        </h3>
        <div className={flash ? 'srcitem flash' : 'srcitem'} ref={srcRef}>
          <div className="snum">1</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600, fontSize: 13.5 }}>{cap?.created} 대화</div>
            <div style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>{top.title} · 마스킹됨</div>
          </div>
          <button
            className="evidence"
            style={{ padding: '5px 9px' }}
            onClick={() => navigate(`/source/${top.captureId}`)}
          >
            원본 보기
          </button>
        </div>

        {related.length > 0 && (
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
              관련 기억 ({related.length})
            </h3>
            {related.map((x) => {
              const rr = matchReason(x, question)
              return (
                <button
                  key={x.id}
                  className="listrow"
                  style={{ marginBottom: 8 }}
                  onClick={() => navigate(`/memories/${x.id}`)}
                >
                  <TypeTag type={x.type} />
                  <div className="body">
                    <div className="t" style={{ fontSize: 14 }}>
                      {x.title}
                    </div>
                    <div className="s">{memSummary(x).slice(0, 54)}</div>
                  </div>
                  <span className={`matchreason ${rr.cls}`}>{rr.label}</span>
                  <RecurBadge memory={x} />
                  <span className="chev">›</span>
                </button>
              )
            })}
          </>
        )}
      </div>

      <div className="note">
        <b>설계</b>
        <span>
          같은 문제를 다시 물으면 <b>새로 풀지 않고</b> 예전 해결·근거를 회상 → "몇 번째 마주침"까지
          보여줘 반복 실수를 드러냄. 근거 없는 답은 안 만듦.
        </span>
      </div>
    </section>
  )
}
