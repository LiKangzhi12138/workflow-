<template>
  <DatasetManagePanel
    title="客户端本地数据集路径登记"
    desc="登记你自己电脑上的数据集目录或 zip 路径。这里只做登记与管理，不会默认永久上传数据集；独立验证时仍需重新选择 zip 包临时上传。"
  />
</template>

<script setup lang="ts">
import DatasetManagePanel from '@/components/dataset/DatasetManagePanel.vue'
</script>
