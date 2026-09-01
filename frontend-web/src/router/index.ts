import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import { buildDocumentTitle, SYSTEM_NAME_SHORT } from '@/constants/system'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import ClientDashboardView from '@/views/client/ClientDashboardView.vue'
import ClientDatasetView from '@/views/client/ClientDatasetView.vue'
import ClientModelView from '@/views/client/ClientModelView.vue'
import ClientValidationView from '@/views/client/ClientValidationView.vue'
import ClientWorkflowManageView from '@/views/client/ClientWorkflowManageView.vue'
import ClientWorkflowProgressView from '@/views/client/ClientWorkflowProgressView.vue'
import ServerDashboardView from '@/views/server/ServerDashboardView.vue'
import ServerDatasetView from '@/views/server/ServerDatasetView.vue'
import ServerModelView from '@/views/server/ServerModelView.vue'
import ServerValidationView from '@/views/server/ServerValidationView.vue'
import ServerWorkflowManageView from '@/views/server/ServerWorkflowManageView.vue'
import ServerWorkflowProgressView from '@/views/server/ServerWorkflowProgressView.vue'
import { getLoginUser, isLoggedIn } from '@/utils/auth'

const clientMenus = [
  { label: '概览', path: '/client/dashboard' },
  { label: '工作流管理', path: '/client/workflows' },
  { label: '工作流进度', path: '/client/progress' },
  { label: '模型路径登记', path: '/client/models' },
  { label: '数据集路径登记', path: '/client/datasets' },
  { label: '独立验证', path: '/client/validation' }
]

const serverMenus = [
  { label: '概览', path: '/server/dashboard' },
  { label: '工作流管理', path: '/server/workflows' },
  { label: '工作流进度', path: '/server/progress' },
  { label: '模型管理', path: '/server/models' },
  { label: '数据集管理', path: '/server/datasets' },
  { label: '独立验证', path: '/server/validation' }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/login'
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { pageTitle: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
      meta: { pageTitle: '注册' }
    },
    {
      path: '/client',
      component: AppLayout,
      meta: {
        requiresAuth: true,
        role: 'CLIENT',
        title: '客户端',
        menus: clientMenus
      },
      children: [
        {
          path: 'dashboard',
          name: 'client-dashboard',
          component: ClientDashboardView,
          props: {
            title: '客户端概览',
            desc: '查看当前账号下的工作流、验证记录、路径登记项和正式业务状态。',
            role: 'CLIENT'
          },
          meta: { pageTitle: '客户端概览' }
        },
        {
          path: 'workflows',
          name: 'client-workflows',
          component: ClientWorkflowManageView,
          meta: { pageTitle: '客户端工作流管理' }
        },
        {
          path: 'progress',
          name: 'client-progress',
          component: ClientWorkflowProgressView,
          meta: { pageTitle: '客户端工作流进度' }
        },
        {
          path: 'models',
          name: 'client-models',
          component: ClientModelView,
          meta: { pageTitle: '客户端模型路径登记' }
        },
        {
          path: 'datasets',
          name: 'client-datasets',
          component: ClientDatasetView,
          meta: { pageTitle: '客户端数据集路径登记' }
        },
        {
          path: 'validation',
          name: 'client-validation',
          component: ClientValidationView,
          meta: { pageTitle: '客户端独立验证' }
        }
      ]
    },
    {
      path: '/server',
      component: AppLayout,
      meta: {
        requiresAuth: true,
        role: 'SERVER',
        title: '服务器端',
        menus: serverMenus
      },
      children: [
        {
          path: 'dashboard',
          name: 'server-dashboard',
          component: ServerDashboardView,
          props: {
            title: '服务器端概览',
            desc: '查看当前服务器账号下的工作流、联邦聚合、正式资产和业务状态。',
            role: 'SERVER'
          },
          meta: { pageTitle: '服务器端概览' }
        },
        {
          path: 'workflows',
          name: 'server-workflows',
          component: ServerWorkflowManageView,
          meta: { pageTitle: '服务器工作流管理' }
        },
        {
          path: 'progress',
          name: 'server-progress',
          component: ServerWorkflowProgressView,
          meta: { pageTitle: '服务器工作流进度' }
        },
        {
          path: 'models',
          name: 'server-models',
          component: ServerModelView,
          meta: { pageTitle: '服务器模型管理' }
        },
        {
          path: 'datasets',
          name: 'server-datasets',
          component: ServerDatasetView,
          meta: { pageTitle: '服务器数据集管理' }
        },
        {
          path: 'validation',
          name: 'server-validation',
          component: ServerValidationView,
          meta: { pageTitle: '服务器独立验证' }
        }
      ]
    }
  ]
})

router.beforeEach((to, _from, next) => {
  const user = getLoginUser()

  if (to.meta.requiresAuth && !isLoggedIn()) {
    next('/login')
    return
  }

  if ((to.path === '/login' || to.path === '/register') && isLoggedIn()) {
    if (user?.roleCode === 'CLIENT') {
      next('/client/dashboard')
    } else if (user?.roleCode === 'SERVER') {
      next('/server/dashboard')
    } else {
      next('/login')
    }
    return
  }

  if (to.meta.requiresAuth && to.meta.role) {
    if (!user || user.roleCode !== to.meta.role) {
      if (user?.roleCode === 'CLIENT') {
        next('/client/dashboard')
      } else if (user?.roleCode === 'SERVER') {
        next('/server/dashboard')
      } else {
        next('/login')
      }
      return
    }
  }

  next()
})

router.afterEach((to) => {
  const matched = [...to.matched].reverse().find((item) => item.meta?.pageTitle || item.meta?.title)
  const title = (matched?.meta?.pageTitle || matched?.meta?.title || SYSTEM_NAME_SHORT) as string
  document.title = buildDocumentTitle(title)
})

export default router
