import { createContext } from 'react'

/** 토스트 표시 함수(메시지 한 줄). */
export type ShowToast = (message: string) => void

export const ToastContext = createContext<ShowToast | null>(null)
