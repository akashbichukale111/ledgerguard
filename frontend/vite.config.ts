import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Dev-server proxy.
 *
 * Login lives on the auth server and everything else on the query service, so `/api/v1/auth` is
 * routed separately and must be declared first — a single `/api` rule would send login to a
 * service that does not implement it.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/v1/auth': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
    },
  },
})
