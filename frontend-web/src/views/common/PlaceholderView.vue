<template>
  <div class="page">
    <el-card shadow="never">
      <div class="title">{{ title }}</div>
      <div class="desc">{{ desc }}</div>

      <el-empty description="该页面已预留，下一轮接真实接口" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  desc: string
}>()
</script>

<style scoped>
.page {
  padding: 16px;
}

.title {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 8px;
}

.desc {
  color: #909399;
  margin-bottom: 24px;
}
</style>