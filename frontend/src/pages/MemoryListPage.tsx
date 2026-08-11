import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { filterMemories, memSummary } from '../lib/search'
import { TYPE_META } from '../lib/typeMeta'
import { StatusPill } from '../components/StatusPill'
import { RecurBadge } from '../components/RecurBadge'

export function MemoryListPage() {
  const { memories } = useRecall()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')

  const active = memories.filter((m) => m.status === 'active')
  const tsN = active.filter((m) => m.type === 'ts').length
  const knN = active.filter((m) => m.type === 'kn').length
  const list = filterMemories(memories, search)

  return (
    <section className="screen">
      <div className="eyebrow">내 기억</div>
      <h1 className="h1">되찾을 수 있게 쌓인 것들</h1>
      <p className="lede">
        유형은 저장할 때 직접 골라요 — 🔧 트러블슈팅 · 📘 지식. 뜻으로도, 정확한 단어로도 검색돼요.
      </p>

      <div className="searchbar">
        <input
          type="text"
          value={search}
          placeholder="🔎 '권한', 'RRF'… 검색"
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <div className="filters">
        <span
          className="type-tag"
          style={{ background: 'var(--accent-soft)', color: 'var(--accent-ink)' }}
        >
          전체 {active.length}
        </span>
        <span className="type-tag">
          <span className="dot" style={{ background: 'var(--ts)' }} />
          트러블슈팅 {tsN}
        </span>
        <span className="type-tag">
          <span className="dot" style={{ background: 'var(--kn)' }} />
          지식 {knN}
        </span>
      </div>

      {list.length === 0 ? (
        <div className="card empty">검색 결과가 없어요.</div>
      ) : (
        <div className="grid">
          {list.map((m) => {
            const meta = TYPE_META[m.type]
            return (
              <button key={m.id} className="mem" onClick={() => navigate(`/memories/${m.id}`)}>
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
                    📎 원본 1
                  </span>
                </div>
              </button>
            )
          })}
        </div>
      )}
    </section>
  )
}
