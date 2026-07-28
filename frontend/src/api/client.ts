import axios from 'axios'

const API_BASE = '/api/v1'

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
