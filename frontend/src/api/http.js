import axios from 'axios'
import { ElMessage } from 'element-plus'
import { auth } from '../store/auth'
import router from '../router'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.request.use((config) => {
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

http.interceptors.response.use(
  (response) => {
    const type = response.config.responseType
    if (type === 'blob' || type === 'arraybuffer' || type === 'text') return response
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        ElMessage.error(body.msg || '请求失败')
        return Promise.reject(new Error(body.msg || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.msg || error.message || '网络错误'
    if (status === 401) {
      auth.clear()
      ElMessage.error('登录已过期，请重新登录')
      router.replace('/login')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

export default http
