<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">{{ SYSTEM_NAME_SHORT }}</div>

      <el-menu
        :default-active="activeMenu"
        class="menu"
        @select="handleSelect"
      >
        <el-menu-item
          v-for="item in menus"
          :key="item.path"
          :index="item.path"
        >
          {{ item.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <div class="system-title">{{ layoutTitle }}</div>
          <div class="system-subtitle">{{ SYSTEM_NAME_FULL }}</div>
        </div>

        <div class="header-right">
          <el-tag>{{ user?.roleCode || 'UNKNOWN' }}</el-tag>
          <span class="username">{{ user?.username || '未命名用户' }}</span>
          <el-button text type="danger" @click="logout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearLoginUser, getLoginUser } from '@/utils/auth'
import { SYSTEM_NAME_FULL, SYSTEM_NAME_SHORT } from '@/constants/system'

type MenuItem = {
  label: string
  path: string
}

const route = useRoute()
const router = useRouter()

const user = computed(() => getLoginUser())

const menus = computed<MenuItem[]>(() => {
  const target = [...route.matched].reverse().find((item) => item.meta?.menus)
  return (target?.meta?.menus as MenuItem[]) || []
})

const layoutTitle = computed(() => {
  const target = [...route.matched].reverse().find((item) => item.meta?.title)
  return (target?.meta?.title as string) || SYSTEM_NAME_SHORT
})

const activeMenu = computed(() => route.path)

function handleSelect(path: string) {
  router.push(path)
}

function logout() {
  clearLoginUser()
  router.replace('/login')
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
  background: #f5f7fa;
}

.aside {
  background: linear-gradient(180deg, #1f2d3d 0%, #27384d 100%);
  color: #fff;
  box-shadow: 2px 0 10px rgba(0, 0, 0, 0.08);
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-size: 20px;
  font-weight: 700;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.menu {
  border-right: none;
  background: transparent;
}

.menu :deep(.el-menu-item) {
  color: #d5dde6;
}

.menu :deep(.el-menu-item.is-active) {
  background: rgba(64, 158, 255, 0.18);
  color: #fff;
}

.header {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
}

.header-left {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}

.system-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.system-subtitle {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: min(720px, 54vw);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.username {
  color: #606266;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main {
  padding: 16px;
  min-width: 0;
  overflow-x: hidden;
}

@media (max-width: 900px) {
  .aside {
    width: 188px !important;
  }

  .system-subtitle {
    display: none;
  }

  .username {
    max-width: 96px;
  }
}
</style>
