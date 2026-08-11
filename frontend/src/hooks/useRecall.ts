import { useContext } from 'react'
import { RecallContext, type RecallStore } from '../store/recallContext'

/** mock 데이터 스토어 접근. RecallProvider 안에서만 사용. */
export function useRecall(): RecallStore {
  const store = useContext(RecallContext)
  if (!store) throw new Error('useRecall must be used within RecallProvider')
  return store
}
