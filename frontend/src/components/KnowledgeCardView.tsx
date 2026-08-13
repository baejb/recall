import { useState } from 'react'

interface KnowledgeCardViewProps {
  summary?: string | null
  facts?: string[]
  keywords?: string[]
  document?: string | null
}

/**
 * 지식 카드 구조화 렌더(Unit O2) — summary/facts/keywords/document를 각각 보여준다.
 * document는 마크다운 라이브러리 없이 `pre-wrap`으로만 표시(무분별한 패키지 금지) — 기본 접힘.
 * 값이 없는 섹션은 아무것도 렌더하지 않는다.
 */
export function KnowledgeCardView({
  summary,
  facts = [],
  keywords = [],
  document,
}: KnowledgeCardViewProps) {
  const [open, setOpen] = useState(false)

  const hasSummary = !!summary && summary.trim() !== ''
  const hasFacts = facts.length > 0
  const hasKeywords = keywords.length > 0
  const hasDocument = !!document && document.trim() !== ''

  if (!hasSummary && !hasFacts && !hasKeywords && !hasDocument) {
    return (
      <div className="v" style={{ color: 'var(--text-faint)' }}>
        (내용 없음)
      </div>
    )
  }

  return (
    <div>
      {hasSummary && (
        <p className="v" style={{ fontSize: 15, lineHeight: 1.6, margin: '0 0 14px' }}>
          {summary}
        </p>
      )}

      {hasFacts && (
        <div className="kv">
          <div className="k">핵심 사실</div>
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {facts.map((f, i) => (
              <li key={i} className="v" style={{ marginBottom: 4 }}>
                {f}
              </li>
            ))}
          </ul>
        </div>
      )}

      {hasKeywords && (
        <div className="row" style={{ marginBottom: 14 }}>
          {keywords.map((k, i) => (
            <span key={`${k}-${i}`} className="type-tag">
              {k}
            </span>
          ))}
        </div>
      )}

      {hasDocument && (
        <div>
          <button type="button" className="chipbtn" onClick={() => setOpen((o) => !o)}>
            {open ? '원문 정리 접기 ▲' : '원문 정리 보기 ▼'}
          </button>
          {open && (
            <div className="docblock" style={{ marginTop: 10 }}>
              {document}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
