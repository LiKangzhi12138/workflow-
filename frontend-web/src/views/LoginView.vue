<template>
  <div class="page-container">
    <el-card class="form-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title">{{ SYSTEM_NAME_FULL }}</div>
          <div class="card-subtitle">登录后按客户端或服务端角色进入 {{ SYSTEM_NAME_SHORT }}</div>
        </div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            clearable
          />
        </el-form-item>

        <el-button
          type="primary"
          class="submit-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登录
        </el-button>

        <div class="footer-link">
          还没有账号？
          <router-link to="/register">去注册</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { loginApi, resolveLoginUrl } from '@/api/auth'
import { saveLoginUser } from '@/utils/auth'
import { SYSTEM_NAME_FULL, SYSTEM_NAME_SHORT } from '@/constants/system'

const router = useRouter()
const loading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

function isSuccess(code: unknown) {
  return code === 'OK' || code === 200 || code === '200'
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const payload = {
    username: form.username,
    password: form.password
  }

  console.debug('[LoginView] submit', {
    finalUrl: resolveLoginUrl(),
    method: 'POST',
    payloadKeys: Object.keys(payload),
    payload
  })

  loading.value = true
  try {
    const res: any = await loginApi(payload)
    console.debug('[LoginView] response', res)

    if (!isSuccess(res?.code)) {
      ElMessage.error(res?.message || '登录失败')
      return
    }

    saveLoginUser(res.data)
    const roleCode = res?.data?.roleCode || res?.data?.user?.roleCode

    if (!roleCode) {
      ElMessage.error('登录成功，但未返回角色信息')
      return
    }

    ElMessage.success('登录成功')

    if (roleCode === 'CLIENT') {
      router.push('/client/dashboard')
    } else {
      router.push('/server/dashboard')
    }
  } catch (error: any) {
    ElMessage.error(error.message || '登录请求失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page-container {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background:
    radial-gradient(circle at top left, rgba(64, 158, 255, 0.16), transparent 35%),
    radial-gradient(circle at bottom right, rgba(103, 194, 58, 0.14), transparent 30%),
    #f5f7fa;
}

.form-card {
  width: 460px;
  border-radius: 18px;
}

.card-header {
  text-align: center;
}

.card-title {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  line-height: 1.5;
}

.card-subtitle {
  margin-top: 8px;
  font-size: 13px;
  color: #909399;
}

.submit-btn {
  width: 100%;
}

.footer-link {
  margin-top: 18px;
  text-align: center;
  color: #606266;
}
</style>
