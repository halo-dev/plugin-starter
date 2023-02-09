import { fileURLToPath, URL } from "url";

import { defineConfig } from "vite";
import Vue from "@vitejs/plugin-vue";
import VueJsx from "@vitejs/plugin-vue-jsx";
import Icons from "unplugin-icons/vite";

const pluginEntryName = "PluginStarter";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [Vue(), VueJsx(), Icons({ compiler: "vue3" })],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    outDir: fileURLToPath(
      new URL("../src/main/resources/console", import.meta.url)
    ),
    emptyOutDir: true,
    lib: {
      entry: "src/index.ts",
      name: pluginEntryName,
      formats: ["iife"],
      fileName: () => "main.js",
    },
    rollupOptions: {
      external: [
        "vue",
        "@halo-dev/console-shared",
        "@halo-dev/components",
        "vue-router",
      ],
      output: {
        globals: {
          vue: "Vue",
          "vue-router": "VueRouter",
          "@halo-dev/components": "HaloComponents",
          "@halo-dev/console-shared": "HaloConsoleShared",
        },
        extend: true,
      },
    },
  },
});
