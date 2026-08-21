import { useState } from 'react'
import type { TsAttempt, TsFields } from '../types'
import { StatusPill } from './StatusPill'

/** 시도 판정별 표시 기호. 판단 불가(unknown)는 성공/실패로 위장하지 않는다. */
const OUTCOME_MARK: Record<TsAttempt['outcome'], string> = {
  failed: '✗',
  partial: '△',
  worked: '✓',
  unknown: '·',
}

/**
 * 트러블슈팅 카드 구조화 렌더 — 증상·에러·환경·시도·원인·해결·상태를 각각 보여준다.
 * 값이 없는 항목은 라벨조차 렌더하지 않는다(빈 칸을 근거처럼 보이게 하지 않는다).
 * 에러 원문은 길 수 있어 기본 접힘(지식 카드의 '원문 정리'와 같은 방식).
 */
export function TroubleshootingCardView({ fields }: { fields: TsFields }) {
  const [errorOpen, setErrorOpen] = useState(false)

  const {
    summary,
    symptom,
    errorMessage,
    errorSignature,
    environment,
    attempts,
    rootCause,
    finalSolution,
  } = fields

  const errorHeadline = errorSignature || errorMessage
  // 시그니처와 원문이 다를 때만 원문을 따로 접어 둔다(같은 내용을 두 번 보여주지 않는다).
  const hasErrorBody = !!errorMessage && errorMessage !== errorHeadline
  const isEmpty =
    !summary &&
    !symptom &&
    !errorHeadline &&
    !environment &&
    attempts.length === 0 &&
    !rootCause &&
    !finalSolution

  if (isEmpty) {
    return (
      <div className="v" style={{ color: 'var(--text-faint)' }}>
        (내용 없음)
      </div>
    )
  }

  return (
    <div>
      {summary && (
        <p className="v" style={{ fontSize: 15, lineHeight: 1.6, margin: '0 0 4px' }}>
          {summary}
        </p>
      )}
      <div className="kv">
        {symptom && (
          <>
            <div className="k">증상</div>
            <div className="v">{symptom}</div>
          </>
        )}

        {errorHeadline && (
          <>
            <div className="k">에러</div>
            <div className="v">
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13.5 }}>{errorHeadline}</div>
              {hasErrorBody && (
                <div style={{ marginTop: 8 }}>
                  <button type="button" className="chipbtn" onClick={() => setErrorOpen((o) => !o)}>
                    {errorOpen ? '에러 원문 접기 ▲' : '에러 원문 보기 ▼'}
                  </button>
                  {errorOpen && (
                    <div className="docblock" style={{ marginTop: 8 }}>
                      {errorMessage}
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}

        {environment && (
          <>
            <div className="k">환경</div>
            <div className="v">{environment}</div>
          </>
        )}

        {attempts.length > 0 && (
          <>
            <div className="k">시도</div>
            <div className="v">
              <ul className="attempts">
                {attempts.map((a, i) => (
                  <li key={i}>
                    <span className={`mark ${a.outcome}`}>{OUTCOME_MARK[a.outcome]}</span>
                    <span>
                      {a.action}
                      {a.result && <span className="arrow"> → </span>}
                      {a.result}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </>
        )}

        {rootCause && (
          <>
            <div className="k">원인</div>
            <div className="v">{rootCause}</div>
          </>
        )}

        {finalSolution && (
          <>
            <div className="k">해결</div>
            <div className="v hi">{finalSolution}</div>
          </>
        )}

        <div className="k">상태</div>
        <div className="v">
          <StatusPill status={fields.status} />
        </div>
      </div>
    </div>
  )
}
