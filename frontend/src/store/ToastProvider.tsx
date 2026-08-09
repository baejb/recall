import { useCallback, useRef, useState, type ReactNode } from 'react'
import { ToastContext, type ShowToast } from './toastContext'

const TOAST_MS = 2400

/** 화면 하단 토스트를 띄우는 Provider. 하위 어디서든 useToast()로 호출. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState('')
  const [show, setShow] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  const showToast = useCallback<ShowToast>((message) => {
    setMsg(message)
    setShow(true)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setShow(false), TOAST_MS)
  }, [])

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div className={show ? 'toast show' : 'toast'} role="status" aria-live="polite">
        <span>{msg}</span>
      </div>
    </ToastContext.Provider>
  )
}
