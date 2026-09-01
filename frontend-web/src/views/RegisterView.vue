<template>
  <div class="page-container">
    <el-card class="form-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title">{{ SYSTEM_NAME_FULL }}</div>
          <div class="card-subtitle">注册后即可进入 {{ SYSTEM_NAME_SHORT }} 的客户端或服务端工作区</div>
        </div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleRegister"
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

        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="form.displayName" placeholder="请输入显示名称" clearable />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" clearable />
        </el-form-item>

        <el-form-item label="角色" prop="roleCode">
          <el-select v-model="form.roleCode" style="width: 100%">
            <el-option label="客户端" value="CLIENT" />
            <el-option label="服务端" value="SERVER" />
          </el-select>
        </el-form-item>

        <el-button
          type="primary"
          class="submit-btn"
          :loading="loading"
          @click="handleRegister"
        >
          注册
        </el-button>

        <div class="footer-link">
          已有账号？
          <router-link to="/login">去登录</router-link>
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
import { registerApi, resolveRegisterUrl } from '@/api/auth'
import { SYSTEM_NAME_FULL, SYSTEM_NAME_SHORT } from '@/constants/system'

const router = useRouter()
const loading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  displayName: '',
  email: '',
  roleCode: 'CLIENT' as 'CLIENT' | 'SERVER'
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: ['blur', 'change'] }
  ],
  roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

function isSuccess(code: unknown) {
  return code === 'OK' || code === 200 || code === '200'
}

async function handleRegister() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const payload = {
    username: form.username,
    password: form.password,
    displayName: form.displayName,
    email: form.email,
    roleCode: form.roleCode
  }

  console.debug('[RegisterView] submit', {
    finalUrl: resolveRegisterUrl(),
    method: 'POST',
    payloadKeys: Object.keys(payload),
    payload
  })

  loading.value = true
  try {
    const res: any = await registerApi(payload)
    console.debug('[RegisterView] response', res)

    if (!isSuccess(res?.code)) {
      ElMessage.error(res?.message || '注册失败')
      return
    }

    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (error: any) {
    ElMessage.error(error.message || '注册请求失败')
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
    radial-gradient(circle at bottom right, rgba(230, 162, 60, 0.14), transparent 30%),
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
