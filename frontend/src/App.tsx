import { useEffect, useState } from 'react'

export default function App() {
  const [pending, setPending] = useState<number | null>(null)

  useEffect(() => {
    fetch('/api/reviews/count')
      .then((r) => r.json())
      .then((d) => setPending(d.pending))
      .catch(() => setPending(null))
  }, [])

  return (
    <main>
      <h1>Recall</h1>
      <p>개발자 개인 기억 시스템 — 초기 스캐폴드.</p>
      <p className="muted">
        검토 대기: {pending === null ? '백엔드 연결 안 됨' : `${pending}건`}
      </p>
    </main>
  )
}
