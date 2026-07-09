import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server on :3000, proxying /api to the backend.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
