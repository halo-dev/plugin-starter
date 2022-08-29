import { definePlugin, BasicLayout } from "@halo-dev/admin-shared";
import DefaultView from "./views/DefaultView.vue";
import { IconGrid } from "@halo-dev/components";
import "./styles/index.css";

export default definePlugin({
  name: "PluginStarter",
  components: [],
  routes: [
    {
      path: "/hello-world",
      component: BasicLayout,
      children: [
        {
          path: "",
          name: "HelloWorld",
          component: DefaultView,
          meta: {
            permissions: ["plugin:apples:view"],
          },
        },
      ],
    },
  ],
  menus: [
    {
      name: "From PluginStarter",
      items: [
        {
          name: "HelloWorld",
          path: "/hello-world",
          icon: IconGrid,
        },
      ],
    },
  ],
  extensionPoints: {},
  activated() {},
  deactivated() {},
});
