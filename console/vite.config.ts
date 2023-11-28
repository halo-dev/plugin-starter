import { fileURLToPath, URL } from "url";

import { defineConfig } from "vite";
import Vue from "@vitejs/plugin-vue";
import VueJsx from "@vitejs/plugin-vue-jsx";
import Icons from "unplugin-icons/vite";
import { HaloUIPluginBundlerKit } from "@halo-dev/ui-plugin-bundler-kit";

export default defineConfig({
  plugins: [
    Vue(),
    VueJsx(),
    Icons({ compiler: "vue3" }),
    HaloUIPluginBundlerKit(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
