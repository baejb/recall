import { useContext } from 'react'
import { ToastContext, type ShowToast } from '../store/toastContext'

/** 토스트 표시 함수. ToastProvider 안에서만 사용. */
export function useToast(): ShowToast {
  const show = useContext(ToastContext)
  if (!show) throw new Error('useToast must be used within ToastProvider')
  return show
}
