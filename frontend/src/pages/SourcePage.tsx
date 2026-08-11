import { Fragment } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { MASK_TOKEN } from '../lib/masking'

/** 마스킹 토큰을 하이라이트하며 원문을 렌더. */
function renderMasked(text: string) {
  const parts = text.split(MASK_TOKEN)
  return parts.map((p, i) => (
    <Fragment key={i}>
      {p}
      {i < parts.length - 1 && <span className="mask">{MASK_TOKEN}</span>}
    </Fragment>
  ))
}

export function SourcePage() {
  const { captureId } = useParams()
  const { getCapture } = useRecall()
  const navigate = useNavigate()
  const c = captureId ? getCapture(captureId) : undefined

  return (
    <section className="screen">
      <button className="backbtn" onClick={() => navigate(-1)}>
        ← 돌아가기
      </button>
      <div className="eyebrow">근거 · 원본</div>
      <h1 className="h1">저장된 원본 대화</h1>
      <p className="lede">
        이 기억이 어디서 나왔는지 그대로 볼 수 있어요. 비밀은 가려진 채예요 — 지어낸 게 아니라는
        증거.
      </p>
      <div className="card pad">
        <div className="between" style={{ marginBottom: 12 }}>
          <span className="eyebrow">source: chat · {c?.created}</span>
          {c && c.spans.length > 0 ? (
            <span className="pill warn">🔒 비밀 {c.spans.length}건 가림</span>
          ) : (
            <span className="pill ok">비밀 없음</span>
          )}
        </div>
        <div className="codeblock">{c ? renderMasked(c.masked) : '(원본 없음)'}</div>
        <div className="note">
          <b>설계</b>
          <span>원본은 검색 대상이 아니라 근거 전용. 콕 집어 꺼내 보여주기만 해요.</span>
        </div>
      </div>
    </section>
  )
}
