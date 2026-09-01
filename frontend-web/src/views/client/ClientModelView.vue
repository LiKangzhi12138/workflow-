<template>
  <ModelManagePanel
    title="客户端本地模型路径登记"
    desc="登记你自己电脑上的模型路径、名称和备注。这里默认不会永久上传模型文件；真正发起独立验证或工作流上传时，仍需重新选择文件。"
  />
</template>

<script setup lang="ts">
import ModelManagePanel from '@/components/model/ModelManagePanel.vue'
</script>
