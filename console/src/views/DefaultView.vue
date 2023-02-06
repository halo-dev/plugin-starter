<script lang="ts">
import {defineComponent} from "vue";
import logo from "../assets/logo.svg";

export default defineComponent({
  name: "DefaultView",
  setup() {
    return {
      logo,
    };
  },
});
</script>
<template>
  <div class="app">
    <div class="title">Hello World</div>
    <img v-permission="['plugin:apples:view']" :src="logo" alt="logo"/>
  </div>
</template>
<style scoped>
.app {
  background-color: #fff;
  width: 100%;
}
.title {
  color: red;
}
img {
  width: 64px;
}
</style>
