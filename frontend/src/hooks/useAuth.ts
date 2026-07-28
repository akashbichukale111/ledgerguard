import { useState, useEffect } from 'react'

interface AuthUser {
  name: string
  roles: string[]
  permittedOperations: string[]
}

interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  user?: AuthUser
}

export function useAuth(): AuthState {
  const [authState, setAuthState] = useState<AuthState>({
    isAuthenticated: false,
    isLoading: true,
  })

  useEffect(() => {
    const credential = localStorage.getItem('auth_token')
    const user = localStorage.getItem('user')

    if (credential && user) {
      try {
        setAuthState({
          isAuthenticated: true,
          isLoading: false,
          user: JSON.parse(user),
        })
        return
      } catch {
        // Corrupt storage should log the operator out, not wedge the app on a parse error.
        localStorage.removeItem('auth_token')
        localStorage.removeItem('auth_scheme')
        localStorage.removeItem('user')
      }
    }
    setAuthState({ isAuthenticated: false, isLoading: false })
  }, [])

  return authState
}

/**
 * Verifies credentials against the auth server and stores what the API client needs.
 *
 * What comes back is a base64 HTTP Basic credential, not a token: it does not expire and it is
 * the password. Keeping it in localStorage is a demo-grade decision — see
 * docs/phase-reports/phase-17.md.
 */
export function useLogin() {
  return async (username: string, password: string) => {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })

    if (!response.ok) {
      return false
    }

    const data = await response.json()
    localStorage.setItem('auth_token', data.token)
    localStorage.setItem('auth_scheme', data.scheme)
    localStorage.setItem('user', JSON.stringify(data.user))
    return true
  }
}

export function useLogout() {
  return () => {
    localStorage.removeItem('auth_token')
    localStorage.removeItem('auth_scheme')
    localStorage.removeItem('user')
  }
}

/** Whether the signed-in operator may perform an operation, for hiding controls they cannot use. */
export function useCan(operation: string): boolean {
  const { user } = useAuth()
  return user?.permittedOperations?.includes(operation) ?? false
}
