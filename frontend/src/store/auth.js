import { reactive } from 'vue'

const TOKEN_KEY = 'acd_token'
const USER_KEY = 'acd_user'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const auth = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: readUser(),
  get isAdmin() {
    return this.user?.role === 1
  },
  get isEnterprise() {
    return this.user?.role === 2
  },
  get roleLabel() {
    if (this.user?.role === 1) return '管理员'
    if (this.user?.role === 2) return '企业需求方'
    return '个人用户'
  },
  setSession(token, user) {
    this.token = token
    this.user = user
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  setUser(user) {
    this.user = user
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear() {
    this.token = ''
    this.user = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
})
