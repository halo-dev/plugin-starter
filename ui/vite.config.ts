import { fileURLToPath, URL } from "url";

import { viteConfig } from "@halo-dev/ui-plugin-bundler-kit";
import Icons from "unplugin-icons/vite";

// For more info,
// please see https://github.com/halo-dev/halo/tree/main/ui/packages/ui-plugin-bundler-kit
export default viteConfig({
  vite: {
    plugins: [Icons({ compiler: "vue3" })],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
  },
});
