import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMemoryList, PAGE_SIZE } from '../hooks/useMemoryList'
import { memSummary } from '../lib/search'
import { TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'
import { RecurBadge } from '../components/RecurBadge'
import type { Memory } from '../types'

/** 유형 탭. counts가 없으면(로딩/에러) 숫자는 생략한다. */
function TypeTab({
  active,
  label,
  count,
  dotVar,
  onClick,
}: {
  active: boolean
  label: string
  count?: number
  dotVar?: string
  onClick: () => void
}) {
  return (
    <button type="button" className={active ? 'type-tag on' : 'type-tag'} onClick={onClick}>
      {dotVar && <span className="dot" style={{ background: `var(${dotVar})` }} />}
      {label}
      {count !== undefined ? ` ${count}` : ''}
    </button>
  )
}

function MemoryCard({ m, onOpen }: { m: Memory; onOpen: () => void }) {
  const meta = TYPE_META[m.type]
  return (
    <button className="mem" onClick={onOpen}>
      <div className="between">
        <span className="type-tag">
          <span className="dot" style={{ background: `var(${meta.varc})` }} />
          {meta.short}
        </span>
        <span
          style={{
            display: 'flex',
            gap: 6,
            alignItems: 'center',
            flexWrap: 'wrap',
            justifyContent: 'flex-end',
          }}
        >
          <RecurBadge memory={m} />
          {m.type === 'ts' && <StatusPill status={m.ts.status} />}
        </span>
      </div>
      <div className="t">{m.title}</div>
      <div className="s">{memSummary(m).slice(0, 70)}</div>
      <div className="foot">
        <span className="date">{m.created}</span>
        <span className="evidence" style={{ padding: '3px 8px' }}>
          § 원본 1
        </span>
      </div>
    </button>
  )
}

/** 내 기억 목록 — 서버사이드 키셋 페이지네이션 + 제목검색 + 유형필터 + 무한 스크롤. */
export function MemoryListPage() {
  const navigate = useNavigate()
  const {
    items,
    counts,
    loading,
    loadingMore,
    error,
    hasMore,
    query,
    scope,
    setQuery,
    setScope,
    loadMore,
    reload,
  } = useMemoryList()

  const sentinelRef = useRef<HTMLDivElement | null>(null)

  // 무한 스크롤: sentinel이 화면에 들어오면 다음 페이지를 당긴다. 더 없을 때(hasMore=false)는 관찰하지 않는다.
  useEffect(() => {
    const el = sentinelRef.current
    if (!el || !hasMore) return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMore()
      },
      { rootMargin: '240px' }
    )
    io.observe(el)
    return () => io.disconnect()
  }, [hasMore, loadMore])

  return (
    <section className="screen">
      <div className="eyebrow">내 기억</div>
      <h1 className="h1">되찾을 수 있게 쌓인 것들</h1>
      <p className="lede">
        유형은 저장할 때 직접 골라요 — 트러블슈팅 · 지식. 제목으로 검색하고, 유형으로 걸러요.
      </p>

      <div className="searchbar">
        <input
          type="text"
          value={query}
          placeholder="'권한', 'RRF'… 제목 검색"
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      <div className="filters">
        <TypeTab
          active={scope === 'all'}
          label="전체"
          count={counts?.total}
          onClick={() => setScope('all')}
        />
        <TypeTab
          active={scope === 'ts'}
          label="트러블슈팅"
          count={counts?.ts}
          dotVar="--ts"
          onClick={() => setScope('ts')}
        />
        <TypeTab
          active={scope === 'kn'}
          label="지식"
          count={counts?.kn}
          dotVar="--kn"
          onClick={() => setScope('kn')}
        />
      </div>

      {error && items.length === 0 ? (
        <div className="card empty">
          <div className="big">
            <svg viewBox="0 0 24 24">
              <path d="M12 3l9 16H3z" />
              <path d="M12 10v4M12 17h.01" />
            </svg>
          </div>
          <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>불러오지 못했어요</p>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '0 0 14px' }}>{error}</p>
          <button className="btn" onClick={reload}>
            다시 시도
          </button>
        </div>
      ) : loading ? (
        <div className="card pad">불러오는 중…</div>
      ) : items.length === 0 ? (
        <div className="card empty">
          {query ? '검색 결과가 없어요.' : '아직 저장된 기억이 없어요.'}
        </div>
      ) : (
        <>
          <div className="grid">
            {items.map((m) => (
              <MemoryCard key={m.id} m={m} onOpen={() => navigate(`/memories/${m.id}`)} />
            ))}
          </div>

          {/* 무한 스크롤 앵커 + 상태 노출(조용한 실패 금지) */}
          <div ref={sentinelRef} aria-hidden="true" />
          {loadingMore && (
            <div className="card pad" style={{ textAlign: 'center', marginTop: 12 }}>
              더 불러오는 중…
            </div>
          )}
          {error && items.length > 0 && (
            <p className="lede" style={{ textAlign: 'center', color: 'var(--bad)', marginTop: 12 }}>
              다음 페이지를 불러오지 못했어요 —{' '}
              <button className="btn" onClick={loadMore}>
                재시도
              </button>
            </p>
          )}
          {hasMore && !loadingMore && !error && (
            <div style={{ textAlign: 'center', marginTop: 12 }}>
              <button className="btn" onClick={loadMore}>
                더 보기
              </button>
            </div>
          )}
          {/* end-of-list 표시는 실제로 페이지가 넘어간(한 페이지 초과) 목록에서만 — 짧은 목록엔 노이즈. */}
          {!hasMore && items.length > PAGE_SIZE && (
            <p className="lede" style={{ textAlign: 'center', marginTop: 16 }}>
              마지막이에요.
            </p>
          )}
        </>
      )}
    </section>
  )
}
