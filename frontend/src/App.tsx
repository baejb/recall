import { useEffect, useState } from 'react'

export default function App() {
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/health')
      .then((r) => r.json())
      .then((d) => setStatus(d.status))
      .catch(() => setStatus(null))
  }, [])

  return (
    <main>
      <h1>Recall</h1>
      <p>개발자 개인 기억 시스템 — 실행 골격.</p>
      <p className="muted">
        백엔드: {status === null ? '연결 안 됨' : `연결됨 (${status})`}
      </p>
    </main>
  )
}
