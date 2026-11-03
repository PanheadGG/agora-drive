import { reactive } from 'vue'

const stored = localStorage.getItem('acd_theme')
export const theme = reactive({ dark: stored === 'dark' })

export function applyTheme() {
  document.documentElement.classList.toggle('dark', theme.dark)
  localStorage.setItem('acd_theme', theme.dark ? 'dark' : 'light')
}

export function toggleTheme() {
  theme.dark = !theme.dark
}
