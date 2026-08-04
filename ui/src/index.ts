import { definePlugin } from '@halo-dev/console-shared'
import HomeView from './views/HomeView.vue'
import { markRaw } from 'vue'
import RiRadarLine from '~icons/ri/radar-line'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/traffic-monitor',
        name: 'TrafficMonitor',
        component: HomeView,
        meta: {
          title: 'IP 流量监控',
          searchable: true,
          menu: {
            name: 'IP 流量监控',
            group: '安全',
            icon: markRaw(RiRadarLine),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
