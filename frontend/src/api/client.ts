import axios from 'axios'

/**
 * Same-origin by default.
 *
 * Locally the Vite dev proxy serves `/api`; on Vercel a rewrite in vercel.json forwards it to the
 * gateway. Both keep the console on one origin, so the browser never sends a preflight and CORS
 * cannot be the thing that breaks the demo.
 *
 * VITE_API_BASE_URL overrides it with an absolute URL for the direct-to-gateway setup. That path
 * IS cross-origin and needs CORS configured on the gateway, which is why it is not the default.
 */
const API_BASE = import.meta.env.VITE_API_BASE_URL
  ? `${String(import.meta.env.VITE_API_BASE_URL).replace(/\/+$/, '')}/api/v1`
  : '/api/v1'

const client = axios.create({
  baseURL: API_BASE,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

client.interceptors.request.use((config) => {
  const credential = localStorage.getItem('auth_token')
  // The scheme comes from the login response rather than being assumed. The services accept
  // HTTP Basic today; an earlier version hardcoded `Bearer`, which nothing here has ever issued
  // or validated, so every authenticated call would have been rejected.
  const scheme = localStorage.getItem('auth_scheme') ?? 'Basic'
  if (credential) {
    config.headers.Authorization = `${scheme} ${credential}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    // A stale or rejected credential should return the operator to the login screen rather than
    // leaving every panel showing an unexplained error.
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_scheme')
      localStorage.removeItem('user')
      window.location.reload()
    }
    return Promise.reject(error)
  },
)

export default client
