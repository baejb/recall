import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useActiveCaptures } from '../hooks/useActiveCaptures'

// backend failedStage(classify|extract|judge|review) → 한국어 표시. 모르는 값은 그대로 보여준다
// (조용한 실패 금지 — 매핑에 없다고 정보를 숨기지 않는다).
const STAGE_LABEL: Record<string, string> = {
  classify: '유형 분류',
  extract: '추출',
  judge: '판정',
  review: '검토함 등록',
}

function stageLabel(stage: string | null): string {
  if (!stage) return '알 수 없는 단계'
  return STAGE_LABEL[stage] ?? stage
}

export function ReviewListPage() {
  const { reviews, loading, error, refresh } = useRecall()
  const navigate = useNavigate()
  // 백엔드가 마스킹→추출→판정을 비동기 처리하는 동안(검토함엔 아직 없음) 처리중/실패를 노출.
  // processing 개수가 줄면(= 하나가 끝나 검토함에 새로 올라왔을 가능성) 목록을 새로고침한다.
  const { processing, failed } = useActiveCaptures(() => void refresh())

  return (
    <section className="screen">
      <div className="eyebrow">검토함 · 승인 게이트</div>
      <h1 className="h1">검토함</h1>
      <p className="lede">
        붙여넣은 세션을 확인하고 <b>승인</b>하면 내 기억에 저장돼요.
      </p>

      {processing.length > 0 && (
        <div
          className="card pad"
          style={{
            marginBottom: 14,
            borderLeft: '3px solid var(--accent)',
            fontSize: 13.5,
          }}
        >
          ⏳ {processing.length}건 정리 중 — 곧 검토함에 올라와요
        </div>
      )}

      {failed.length > 0 && (
        <div className="card pad" style={{ marginBottom: 14, borderLeft: '3px solid var(--bad)' }}>
          <p style={{ fontWeight: 600, margin: '0 0 8px', color: 'var(--bad)' }}>
            {failed.length}건 처리 실패
          </p>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, color: 'var(--text-muted)' }}>
            {failed.map((f) => (
              <li key={f.id}>
                #{f.id} — {stageLabel(f.failedStage)} 단계에서 실패
              </li>
            ))}
          </ul>
        </div>
      )}

      {error && (
        <div className="card empty">
          <div className="big">
            <svg viewBox="0 0 24 24">
              <path d="M12 3l9 16H3z" />
              <path d="M12 10v4M12 17h.01" />
            </svg>
          </div>
          <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>불러오지 못했어요</p>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '0 0 14px' }}>{error}</p>
          <button className="btn" onClick={() => void refresh()}>
            다시 시도
          </button>
        </div>
      )}

      {loading && !error && <div className="card pad">불러오는 중…</div>}

      {!loading &&
        !error &&
        (reviews.length === 0
          ? // processing.length > 0 이면 위 "정리 중" 배너가 이미 대기 중인 작업을 알리므로
            // "검토할 게 없어요"(완전한 빈 상태처럼 보임)를 대신 숨긴다.
            processing.length === 0 && (
              <div className="card empty">
                <div className="big">
                  <svg viewBox="0 0 24 24">
                    <circle cx="12" cy="12" r="9" />
                    <path d="M8 12l3 3 5-6" />
                  </svg>
                </div>
                <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>검토할 게 없어요</p>
                <p style={{ fontSize: 13.5, margin: '0 0 14px' }}>
                  새로 붙여넣으면 여기로 올라와요.
                </p>
                <button className="btn" onClick={() => navigate('/capture')}>
                  새로 붙여넣기
                </button>
              </div>
            )
          : reviews.map((r) => {
              const title = r.cards[0] ? r.cards[0].title : '세션'
              return (
                <button key={r.id} className="listrow" onClick={() => navigate(`/reviews/${r.id}`)}>
                  <span className="type-tag">세션</span>
                  <div className="body">
                    <div className="t">{title}</div>
                    <div className="s">승인 대기</div>
                  </div>
                  <span className="pill warn">신규</span>
                  <span className="chev">›</span>
                </button>
              )
            }))}
    </section>
  )
}
