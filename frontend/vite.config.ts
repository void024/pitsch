import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Development: the SPA calls "/api" on its own origin and Vite forwards it to the backend, so the refresh cookie
// is first-party and no CORS is needed. Production: serve the SPA and the API under one site (recommended) or set
// VITE_API_BASE_URL and the backend's CORS_ALLOWED_ORIGINS / COOKIE_SAME_SITE accordingly (see docs/DEPLOYMENT.md).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 600,
  },
})
