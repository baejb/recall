import { Navigate, Route, Routes } from 'react-router-dom'
import { Sidebar } from './components/Sidebar'
import { QueryPage } from './pages/QueryPage'
import { CapturePage } from './pages/CapturePage'
import { ReviewListPage } from './pages/ReviewListPage'
import { ReviewDetailPage } from './pages/ReviewDetailPage'
import { MemoryListPage } from './pages/MemoryListPage'
import { MemoryDetailPage } from './pages/MemoryDetailPage'

export default function App() {
  return (
    <div className="layout">
      <Sidebar />
      <main className="stage">
        <Routes>
          <Route path="/" element={<QueryPage />} />
          <Route path="/capture" element={<CapturePage />} />
          <Route path="/reviews" element={<ReviewListPage />} />
          <Route path="/reviews/:id" element={<ReviewDetailPage />} />
          <Route path="/memories" element={<MemoryListPage />} />
          <Route path="/memories/:id" element={<MemoryDetailPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
