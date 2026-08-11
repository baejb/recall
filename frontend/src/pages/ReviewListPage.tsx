import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'

export function ReviewListPage() {
  const { reviews, getCapture } = useRecall()
  const navigate = useNavigate()

  return (
    <section className="screen">
      <div className="eyebrow">검토함 · 승인 게이트</div>
      <h1 className="h1">승인 대기 중</h1>
      <p className="lede">
        붙여넣은 세션이 대기 중이에요. 열어서 <b>유형을 고르고</b>, 여러 주제면 <b>카드로 나눠</b>{' '}
        승인하세요.
      </p>

      {reviews.length === 0 ? (
        <div className="card empty">
          <div className="big">✅</div>
          <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>검토할 게 없어요</p>
          <p style={{ fontSize: 13.5, margin: '0 0 14px' }}>새로 붙여넣으면 여기로 올라와요.</p>
          <button className="btn" onClick={() => navigate('/capture')}>
            ➕ 새로 붙여넣기
          </button>
        </div>
      ) : (
        reviews.map((r) => {
          const cap = getCapture(r.captureId)
          const title = r.cards[0] ? r.cards[0].title : '세션'
          return (
            <button key={r.id} className="listrow" onClick={() => navigate(`/reviews/${r.id}`)}>
              <span className="type-tag">📋 세션</span>
              <div className="body">
                <div className="t">{title}</div>
                <div className="s">
                  {r.cards.length}개 카드 · {cap?.created} · 승인 대기
                </div>
              </div>
              <span className="pill warn">신규</span>
              <span className="chev">›</span>
            </button>
          )
        })
      )}
    </section>
  )
}
