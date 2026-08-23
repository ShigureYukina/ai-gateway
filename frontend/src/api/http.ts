/**
 * 网络层核心：统一处理鉴权头、token 刷新与错误语义。
 */

let getToken: () => string | null = () => null
let getRefreshToken: () => string | null = () => null
let setToken: (token: string) => void = () => {}
let onAuthFailure: () => void = () => {}

export function setTokenProvider(fn: () => string | null) {
  getToken = fn
}

export function setRefreshTokenProvider(fn: () => string | null) {
  getRefreshToken = fn
}

export function setTokenUpdater(fn: (token: string) => void) {
  setToken = fn
}

export function setOnAuthFailure(fn: () => void) {
  onAuthFailure = fn
}

export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {
    ...(options.headers as Record<string, string>),
  }

  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  if (options.body && typeof options.body === 'string') {
    headers['Content-Type'] = 'application/json'
  }

  const res = await fetch(path, { ...options, headers })

  if (res.status === 401) {
    const rt = getRefreshToken()
    if (rt) {
      try {
        const refreshRes = await fetch('/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: rt }),
        })
        if (refreshRes.ok) {
          const data = await refreshRes.json()
          setToken(data.accessToken)
          // 使用刷新后的 token 原样重试请求，保持既有接口语义不变。
          headers['Authorization'] = `Bearer ${data.accessToken}`
          const retryRes = await fetch(path, { ...options, headers })
          if (retryRes.status === 401) {
            onAuthFailure()
            throw new ApiError('Unauthorized', 401)
          }
          if (!retryRes.ok) {
            const text = await retryRes.text().catch(() => 'Unknown error')
            throw new ApiError(text, retryRes.status)
          }
          if (retryRes.status === 204) return undefined as T
          return retryRes.json()
        }
      } catch {
        // refresh attempt failed — logout
      }
    }
    onAuthFailure()
    throw new ApiError('Unauthorized', 401)
  }

  if (res.status === 403) {
    throw new ApiError('Forbidden', 403)
  }

  if (!res.ok) {
    const text = await res.text().catch(() => 'Unknown error')
    throw new ApiError(text, res.status)
  }

  if (res.status === 204) {
    return undefined as T
  }

  return res.json()
}
