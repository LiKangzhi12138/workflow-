import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearLoginUser } from '@/utils/auth'
import router from '@/router'
import { summarizeUiErrorMessage } from '@/utils/errorMessage'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').trim() || '/api'
const apiTimeoutMs = Number(import.meta.env.VITE_API_TIMEOUT_MS || 600000)

const http = axios.create({
  baseURL: apiBaseUrl,
  timeout: Number.isFinite(apiTimeoutMs) && apiTimeoutMs > 0 ? apiTimeoutMs : 600000,
  withCredentials: true
})

function resolveRequestUrl(config: any) {
  try {
    return http.getUri(config)
  } catch {
    const baseURL = String(config?.baseURL || apiBaseUrl || '')
    const url = String(config?.url || '')
    if (!baseURL) return url
    const normalizedBase = baseURL.endsWith('/') ? baseURL.slice(0, -1) : baseURL
    const normalizedUrl = url.startsWith('/') ? url : `/${url}`
    return `${normalizedBase}${normalizedUrl}`
  }
}

function isAuthRequest(url?: string) {
  const normalizedUrl = String(url || '').toLowerCase()
  return (
    normalizedUrl.includes('/auth/login') ||
    normalizedUrl.includes('/auth/register') ||
    normalizedUrl.endsWith('auth/login') ||
    normalizedUrl.endsWith('auth/register')
  )
}

http.interceptors.request.use((config) => {
  const token = getToken()
  const authRequest = isAuthRequest(config.url)
  const finalUrl = resolveRequestUrl(config)

  config.headers = config.headers ?? {}

  if (authRequest && config.headers.Authorization) {
    delete config.headers.Authorization
  }

  if (token && !authRequest) {
    config.headers.Authorization = token.startsWith('Bearer ')
      ? token
      : `Bearer ${token}`
  }

  console.debug('[http][request]', {
    method: String(config.method || 'get').toUpperCase(),
    baseURL: config.baseURL || apiBaseUrl,
    url: config.url,
    finalUrl,
    withCredentials: config.withCredentials,
    hasToken: Boolean(token),
    authRequest,
    headerKeys: Object.keys(config.headers || {})
  })

  return config
})

http.interceptors.response.use(
  (response) => {
    console.debug('[http][response]', {
      status: response.status,
      url: resolveRequestUrl(response.config),
      data: response.data
    })

    const res = response.data
    if (res?.code && res.code !== 'OK') {
      const message = summarizeUiErrorMessage(res.message, '请求处理失败')

      if (res.code === 'BIZ_ERROR' && message.includes('登录')) {
        clearLoginUser()
        router.replace('/login')
      }

      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }

    return res
  },
  (error) => {
    console.error('[http][response-error]', {
      status: error?.response?.status,
      url: resolveRequestUrl(error?.config),
      data: error?.response?.data,
      message: error?.message
    })

    const rawMessage =
      error?.response?.data?.message ||
      error?.message ||
      '请求失败'
    const message = summarizeUiErrorMessage(rawMessage, '请求处理失败')

    if (error.response?.data?.message) {
      ElMessage.error(message)
    } else if (error.response?.status === 401) {
      ElMessage.error('登录状态已失效，请重新登录')
    } else if (error.response?.status === 403) {
      ElMessage.error('权限不足，无法执行当前操作')
    } else if (error.response?.status === 404) {
      ElMessage.error('请求的接口或资源不存在')
    } else if (error.response?.status === 422) {
      ElMessage.error(message || 'Python 服务请求参数异常，请检查模型、数据集或任务配置')
    } else if (error.response?.status === 500) {
      ElMessage.error(message || '服务端处理失败，请稍后重试或查看后端日志')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error('网络请求失败，请检查服务是否正常运行')
    }

    if (rawMessage && rawMessage !== message) {
      console.error('[http] raw backend error message', rawMessage)
    }

    return Promise.reject(new Error(message))
  }
)

export default http
export { resolveRequestUrl }
