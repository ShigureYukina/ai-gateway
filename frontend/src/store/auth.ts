import { create } from 'zustand'
import { setTokenProvider, setRefreshTokenProvider, setTokenUpdater, setOnAuthFailure } from '@/api/client'

interface AuthState {
  token: string | null
  refreshToken: string | null
  username: string | null
  role: string | null
  isAuthenticated: boolean
  setAuth: (token: string, refreshToken: string, username: string, role: string) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  refreshToken: null,
  username: null,
  role: null,
  isAuthenticated: false,

  setAuth: (token: string, refreshToken: string, username: string, role: string) => {
    set({ token, refreshToken, username, role, isAuthenticated: true })
  },

  logout: () => {
    set({ token: null, refreshToken: null, username: null, role: null, isAuthenticated: false })
  },
}))

// Wire up API client providers to store
setTokenProvider(() => useAuthStore.getState().token)
setRefreshTokenProvider(() => useAuthStore.getState().refreshToken)
setTokenUpdater((token: string) => {
  useAuthStore.setState({ token, isAuthenticated: true })
})
setOnAuthFailure(() => {
  useAuthStore.getState().logout()
})
