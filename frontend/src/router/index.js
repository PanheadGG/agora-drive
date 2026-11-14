import { createRouter, createWebHashHistory } from 'vue-router'
import { auth } from '../store/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      { path: '', name: 'drive', component: () => import('../views/DriveView.vue') },
      { path: 'recycle', name: 'recycle', component: () => import('../views/RecycleView.vue') },
      { path: 'seller', name: 'seller', component: () => import('../views/SellerStudioView.vue') },
      { path: 'market', name: 'market', component: () => import('../views/MarketView.vue') },
      { path: 'collab', name: 'collab', component: () => import('../views/CollabView.vue') },
    ],
  },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('../views/AdminView.vue'),
    meta: { admin: true },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.public) {
    return auth.token ? { name: 'drive' } : true
  }
  if (!auth.token) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return { name: 'drive' }
  return true
})

export default router
