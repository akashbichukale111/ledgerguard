import { useState, useEffect } from 'react'

interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  user?: {
    name: string
    roles: string[]
  }
}

export function useAuth(): AuthState {
  const [authState, setAuthState] = useState<AuthState>({
    isAuthenticated: false,
    isLoading: true,
  })

  useEffect(() => {
    const token = localStorage.getItem('auth_token')
    const user = localStorage.getItem('user')

    if (token && user) {
      setAuthState({
        isAuthenticated: true,
        isLoading: false,
        user: JSON.parse(user),
      })
    } else {
      setAuthState({
        isAuthenticated: false,
        isLoading: false,
      })
    }
  }, [])

  return authState
}

export function useLogin() {
  return async (username: string, password: string) => {
    try {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })

      if (!response.ok) {
        throw new Error('Login failed')
      }

      const data = await response.json()
      localStorage.setItem('auth_token', data.token)
      localStorage.setItem('user', JSON.stringify(data.user))

      return true
    } catch {
      return false
    }
  }
}

export function useLogout() {
  return () => {
    localStorage.removeItem('auth_token')
    localStorage.removeItem('user')
  }
}
