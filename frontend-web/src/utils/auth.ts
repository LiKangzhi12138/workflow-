export type RoleCode = 'CLIENT' | 'SERVER'

export interface LoginUser {
  id?: number
  username?: string
  displayName?: string
  email?: string
  roleCode?: RoleCode
  token?: string
  accessToken?: string
  authToken?: string
}

const LOGIN_USER_KEY = 'loginUser'
const TOKEN_KEYS = ['token', 'accessToken', 'authToken'] as const
const storage = window.sessionStorage

function safeParse<T>(value: string | null): T | null {
  if (!value) return null
  try {
    return JSON.parse(value) as T
  } catch {
    return null
  }
}

function extractToken(payload: any): string {
  return (
    payload?.token ||
    payload?.accessToken ||
    payload?.authToken ||
    payload?.jwt ||
    payload?.authorization ||
    payload?.Authorization ||
    payload?.user?.token ||
    payload?.user?.accessToken ||
    payload?.user?.authToken ||
    ''
  )
}

function normalizeUser(payload: any): LoginUser {
  const source = payload?.user ?? payload ?? {}
  return {
    id: source.id,
    username: source.username,
    displayName: source.displayName,
    email: source.email,
    roleCode: source.roleCode,
    token: source.token || payload?.token,
    accessToken: source.accessToken || payload?.accessToken,
    authToken: source.authToken || payload?.authToken
  }
}

export function saveLoginUser(payload: any) {
  const user = normalizeUser(payload)
  storage.setItem(LOGIN_USER_KEY, JSON.stringify(user))

  const token = extractToken(payload)
  if (token) {
    storage.setItem('token', token)
  } else {
    for (const key of TOKEN_KEYS) {
      storage.removeItem(key)
    }
  }
}

export function getLoginUser(): LoginUser | null {
  return safeParse<LoginUser>(storage.getItem(LOGIN_USER_KEY))
}

export function getToken(): string {
  return (
    storage.getItem('token') ||
    storage.getItem('accessToken') ||
    storage.getItem('authToken') ||
    getLoginUser()?.token ||
    getLoginUser()?.accessToken ||
    getLoginUser()?.authToken ||
    ''
  )
}

export function isLoggedIn(): boolean {
  const user = getLoginUser()
  return !!(user && user.roleCode)
}

export function clearLoginUser() {
  storage.removeItem(LOGIN_USER_KEY)
  for (const key of TOKEN_KEYS) {
    storage.removeItem(key)
  }
}
