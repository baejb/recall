import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.tsx'
import { ToastProvider } from './store/ToastProvider'
import { RecallProvider } from './store/RecallProvider'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <RecallProvider>
          <App />
        </RecallProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>
)
