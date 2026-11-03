<script setup>
import { onMounted, watch } from 'vue'
import { theme, applyTheme } from './store/theme'
import {RouterView} from "vue-router";

onMounted(applyTheme)
watch(() => theme.dark, applyTheme)
</script>

<template>
  <router-view />
</template>
