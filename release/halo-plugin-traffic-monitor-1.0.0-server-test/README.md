# Halo IP 流量监控插件

面向 Halo 2.25.x 的请求级实时流量监控插件。插件通过 Halo `AdditionalWebFilter` 记录前台 HTTP 请求，在 Console 中提供 IP 排名、访问时间柱状图、流量统计与异常告警。

## 已实现

- 实时统计 IP、访问次数、首次/最后访问时间、最后路径和状态码。
- 按 30 分钟、1 小时、6 小时、24 小时显示访问时间柱状图。
- 计算 HTTP request body 和 response body 字节数。
- SSE 每 2 秒推送仪表盘快照，断线时自动降级为 5 秒轮询。
- 异常检测：
  - 单 IP 每分钟请求数超限；
  - 单 IP 每分钟总字节超限；
  - 单次大流量传输；
  - 单 IP 每分钟 5xx 爆发；
  - `.env`、`wp-admin`、`phpmyadmin`、`actuator`等常见扫描路径。
- 告警同时写入 Halo 日志与 Console 告警列表，支持确认处理。
- 默认排除 Console、插件自身 API 和静态资源，避免统计回路。

> 这里的“流量”是 Halo 应用层看到的 HTTP request/response body 字节，不包含 TCP/IP/TLS 包头、CDN 直接回源外流量或未进入 Halo 进程的数据包。

## 安装

1. 确保 Halo 版本为 `2.25.0` 或更高的 2.25.x。
2. 打开 Halo Console -> **插件** -> **安装插件**。
3. 上传：

   ```text
   build/libs/halo-plugin-traffic-monitor-1.0.0.jar
   ```

4. 启用插件，然后从左侧 **安全 -> IP 流量监控** 进入仪表盘。

## 反向代理 IP 配置

直连 Halo 时保持“信任反向代理 IP 请求头”关闭。使用 Nginx 时，先确保代理会覆盖请求头：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

确认请求不会绕过反向代理直连 Halo 后，再在插件设置中开启信任代理请求头。

## 配置项

| 配置 | 默认值 | 含义 |
|---|---:|---|
| 每分钟请求数阈值 | 120 | 单 IP 60 秒滑动窗口 |
| 每分钟流量阈值 | 52,428,800 | request + response bytes |
| 单次流量阈值 | 10,485,760 | 单个 HTTP exchange |
| 每分钟 5xx 阈值 | 10 | 单 IP 60 秒滑动窗口 |
| 告警冷却 | 60 秒 | IP + 告警类型维度 |
| 时序保留 | 24 小时 | 可设置 1–168 小时 |

当前 `1.0.0` 采用进程内滚动窗口：插件重载或 Halo 重启后从空统计开始。

## 构建与测试

需要 JDK 21、Node.js 和 pnpm 9.15.9。建议把源码放在纯 ASCII 路径下，Windows 上部分 Gradle test worker 对中文 classpath 处理异常。

```powershell
corepack prepare pnpm@9.15.9 --activate
corepack enable pnpm
.\gradlew.bat build
```

单独检查 UI：

```powershell
Set-Location .\ui
pnpm install
pnpm type-check
pnpm build-only
```

真实 Halo 运行时 smoke test：

```powershell
.\scripts\runtime-smoke.ps1 `
  -HaloJar C:\path\to\halo-2.25.4.jar `
  -PluginProject C:\ascii-path\halo-plugin-traffic-monitor `
  -JavaHome C:\path\to\jdk-21 `
  -Port 8099
```

smoke test 会启动临时 Halo、完成初始化、装载插件、产生访问流量、触发扫描路径告警、调用仪表盘 API，最后停止 Halo 进程并保留日志。

## Console API

API 前缀：

```text
/apis/console.api.traffic-monitor.halo.run/v1alpha1
```

- `GET /dashboard?minutes=60`
- `GET /summary?minutes=60`
- `GET /timeline?minutes=60&bucketMinutes=1`
- `GET /ips?limit=100`
- `GET /alerts?limit=100`
- `GET /stream?minutes=60`
- `POST /alerts/{id}/acknowledge`
- `DELETE /data`
