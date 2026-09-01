import http from './http'

export interface ApiResponse<T> {
  code: string | number
  message: string
  data: T
}

export interface RuntimeProfile {
  runtimeMode: string
  serverPathImportEnabled: boolean
  serverPathImportAllowed: boolean
  serverPathImportRoots: string[]
}

export function getRuntimeProfileApi() {
  return http.get<ApiResponse<RuntimeProfile>, ApiResponse<RuntimeProfile>>('/system/runtime')
}
