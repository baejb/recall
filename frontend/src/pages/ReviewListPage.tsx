import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useActiveCaptures } from '../hooks/useActiveCaptures'
import { useProcessedReviews } from '../hooks/useProcessedReviews'
import { JUDGEMENT_META } from '../lib/judgement'
import type { Review } from '../types'

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

/** 처리 결과 배지. 반려는 `bad` — 기억이 되지 않았다는 사실이 승인과 같은 무게로 보이면 안 된다. */
const STATUS_META: Record<Review['status'], { label: string; tone: 'ok' | 'warn' | 'bad' }> = {
  pending: { label: '대기', tone: 'warn' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '반려됨', tone: 'bad' },
  UNKNOWN: { label: '상태 불명', tone: 'bad' },
}

/**
 * 처리된 검토 항목 한 줄. 읽기 전용이다 — 되살리기는 별개 결정이라 이 화면의 일이 아니다.
 *
 * 판정 근거를 함께 보여주는 이유: 반려한 이유를 되짚으려고 여는 자리인데, 그게 없으면 제목만 보고 왜
 * 반려했는지 다시 추측해야 한다.
 */
function ProcessedRow({ r, onOpenMemory }: { r: Review; onOpenMemory: (id: string) => void }) {
  const judge = JUDGEMENT_META[r.judgement]
  const status = STATUS_META[r.status]
  return (
    <div className="card pad" style={{ marginBottom: 10 }}>
      <div className="between">
        <div style={{ fontWeight: 600 }}>{r.cards[0]?.title ?? '세션'}</div>
        <span style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <span className={`pill ${judge.tone}`}>{judge.label}</span>
          <span className={`pill ${status.tone}`}>{status.label}</span>
        </span>
      </div>
      {r.judgeReason && (
        <div className="note">
          <b>판정 근거</b>
          <span>{r.judgeReason}</span>
        </div>
      )}
      <div className="note">
        <b>처리</b>
        <span>
          {r.resolved || '시각 기록 없음'} · 원본 #{r.captureId}
          {r.status === 'rejected' && ' · 기억으로 저장되지 않았어요(원본은 보존)'}
        </span>
      </div>
      {r.targetMemoryId && (
        <button
          className="btn sm"
          style={{ marginTop: 10 }}
          onClick={() => onOpenMemory(r.targetMemoryId as string)}
        >
          관련 기억 #{r.targetMemoryId} 열기 ›
        </button>
      )}
    </div>
  )
}

export function ReviewListPage() {
  const { reviews, loading, error, refresh } = useRecall()
  const navigate = useNavigate()
  const [tab, setTab] = useState<'pending' | 'processed'>('pending')
  const processed = useProcessedReviews(tab === 'processed')
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

      <div className="row" style={{ marginBottom: 14 }}>
        <button
          type="button"
          className={tab === 'pending' ? 'type-tag on' : 'type-tag'}
          onClick={() => setTab('pending')}
        >
          승인 대기 {reviews.length}
        </button>
        <button
          type="button"
          className={tab === 'processed' ? 'type-tag on' : 'type-tag'}
          onClick={() => setTab('processed')}
        >
          처리됨
        </button>
      </div>

      {tab === 'processed' && (
        <>
          {processed.error && (
            <div className="card empty">
              <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>불러오지 못했어요</p>
              <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '0 0 14px' }}>
                {processed.error}
              </p>
              <button className="btn" onClick={processed.reload}>
                다시 시도
              </button>
            </div>
          )}
          {processed.loading && !processed.error && <div className="card pad">불러오는 중…</div>}
          {!processed.loading && !processed.error && processed.items.length === 0 && (
            <div className="card empty">
              <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>아직 처리한 게 없어요</p>
              <p style={{ fontSize: 13.5, margin: 0 }}>승인하거나 반려하면 여기에 남아요.</p>
            </div>
          )}
          {!processed.loading &&
            !processed.error &&
            processed.items.map((r) => (
              <ProcessedRow key={r.id} r={r} onOpenMemory={(id) => navigate(`/memories/${id}`)} />
            ))}
        </>
      )}

      {tab === 'pending' && processing.length > 0 && (
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

      {tab === 'pending' && failed.length > 0 && (
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

      {tab === 'pending' && error && (
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

      {tab === 'pending' && loading && !error && <div className="card pad">불러오는 중…</div>}

      {tab === 'pending' &&
        !loading &&
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
