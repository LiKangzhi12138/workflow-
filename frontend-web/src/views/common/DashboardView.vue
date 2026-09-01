<template>
  <div class="page">
    <div class="hero">
      <div>
        <div class="title">{{ title }}</div>
        <div class="desc">{{ desc }}</div>
      </div>
      <el-tag size="large">{{ roleLabel }}</el-tag>
    </div>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="card-title">当前用户</div>
          <div class="card-value">{{ user?.username || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="card-title">角色</div>
          <div class="card-value">{{ roleLabel }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="card-title">系统名称</div>
          <div class="card-value">{{ SYSTEM_NAME_SHORT }}</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { getLoginUser } from '@/utils/auth'
import { ROLE_LABELS, SYSTEM_NAME_SHORT } from '@/constants/system'

const props = defineProps<{
  title: string
  desc: string
  role: string
}>()

const user = computed(() => getLoginUser())
const roleLabel = computed(() => ROLE_LABELS[props.role] || props.role)
</script>

<style scoped>
.page {
  padding: 16px;
}

.hero {
  background: linear-gradient(135deg, #409eff 0%, #79bbff 100%);
  color: #fff;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  font-size: 24px;
  font-weight: 700;
}

.desc {
  margin-top: 8px;
  font-size: 14px;
  opacity: 0.92;
}

.card-title {
  font-size: 14px;
  color: #909399;
}

.card-value {
  margin-top: 10px;
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}
</style>
