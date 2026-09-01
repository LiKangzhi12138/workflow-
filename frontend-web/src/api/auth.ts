import http, { resolveRequestUrl } from './http'

export interface RegisterRequest {
  username: string
  password: string
  displayName: string
  email: string
  roleCode: 'CLIENT' | 'SERVER'
}

export interface LoginRequest {
  username: string
  password: string
}

export interface ApiResponse<T> {
  code: string | number
  message: string
  data: T
}

export const REGISTER_API_PATH = '/auth/register'
export const LOGIN_API_PATH = '/auth/login'

export function resolveRegisterUrl() {
  return resolveRequestUrl({
    baseURL: http.defaults.baseURL,
    url: REGISTER_API_PATH,
    method: 'post'
  })
}

export function resolveLoginUrl() {
  return resolveRequestUrl({
    baseURL: http.defaults.baseURL,
    url: LOGIN_API_PATH,
    method: 'post'
  })
}

export function registerApi(data: RegisterRequest) {
  console.debug('[auth][register]', {
    method: 'POST',
    baseURL: http.defaults.baseURL,
    finalUrl: resolveRegisterUrl(),
    payloadKeys: Object.keys(data || {})
  })
  return http.post<ApiResponse<any>, ApiResponse<any>>(REGISTER_API_PATH, data)
}

export function loginApi(data: LoginRequest) {
  console.debug('[auth][login]', {
    method: 'POST',
    baseURL: http.defaults.baseURL,
    finalUrl: resolveLoginUrl(),
    payloadKeys: Object.keys(data || {})
  })
  return http.post<ApiResponse<any>, ApiResponse<any>>(LOGIN_API_PATH, data)
}
