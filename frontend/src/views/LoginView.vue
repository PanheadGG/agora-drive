<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { auth } from '../store/auth'

const router = useRouter()
const route = useRoute()
const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', confirm: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  if (mode.value === 'register' && form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    const data =
      mode.value === 'login'
        ? await api.login({ username: form.username, password: form.password })
        : await api.register({ username: form.username, password: form.password })
    auth.setSession(data.token, data.user)
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
    router.replace(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}

function fillDemo(username, password) {
  mode.value = 'login'
  form.username = username
  form.password = password
}
</script>

<template>
  <div class="login-page">
    <section class="login-hero">
      <div class="hero-logo">◍</div>
      <h1>Agent 云盘</h1>
      <p class="hero-desc">SpringBoot + Vue3 全栈网盘 Demo：秒传、分片上传、回收站与容量管理。</p>
      <ul class="hero-points">
        <li>文件与文件夹浏览，图片 / 文本在线预览</li>
        <li>分片上传 + sha256 秒传去重</li>
        <li>回收站恢复与彻底删除，容量实时统计</li>
        <li>管理员后台：用户状态与配额管理</li>
      </ul>
    </section>

    <section class="login-form-wrap">
      <el-card class="login-card" shadow="never">
        <el-tabs v-model="mode" stretch>
          <el-tab-pane label="登录" name="login" />
          <el-tab-pane label="注册" name="register" />
        </el-tabs>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" placeholder="3-32 个字符" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="至少 6 位"
              autocomplete="current-password"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="确认密码">
            <el-input
              v-model="form.confirm"
              size="large"
              type="password"
              show-password
              placeholder="再次输入密码"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="submit">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form>

        <div class="demo-hint">
          <span>演示账号：</span>
          <el-link type="primary" @click="fillDemo('admin', 'admin123')">admin / admin123</el-link>
          <span class="dot">·</span>
          <el-link type="primary" @click="fillDemo('demo', 'demo1234')">demo / demo1234</el-link>
        </div>
      </el-card>
    </section>
  </div>
</template>
