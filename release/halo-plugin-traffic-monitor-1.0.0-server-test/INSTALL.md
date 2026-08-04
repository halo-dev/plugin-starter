# Halo IP 流量监控 1.0.0 服务器测试包

## 兼容性

- Halo: `>= 2.25.0`
- 已完成运行时验证的版本：`2.25.4`

## 安装

1. 先备份 Halo 数据库和工作目录。
2. 登录 Halo Console。
3. 进入“插件 -> 安装插件”。
4. 上传 `halo-plugin-traffic-monitor-1.0.0.jar`。
5. 启用插件。
6. 从左侧“安全 -> IP 流量监控”打开仪表盘。

## 上线后验证

1. 用浏览器访问站点前台 3–5 次。
2. 回到仪表盘，确认“访问次数”、“独立 IP”和柱状图已更新。
3. 如果 Halo 在 Nginx/Cloudflare 后面，先确保代理覆盖 `X-Forwarded-For`，再打开插件的“信任反向代理 IP 请求头”。
4. 检查 Halo 日志中是否有 `IP traffic monitor plugin started`。

## 校验文件

PowerShell：

```powershell
Get-FileHash .\halo-plugin-traffic-monitor-1.0.0.jar -Algorithm SHA256
```

Linux：

```bash
sha256sum -c halo-plugin-traffic-monitor-1.0.0.jar.sha256
```

## 注意

- 统计的是进入 Halo 进程的 HTTP request/response body 字节，不等于网卡层数据包大小。
- 1.0.0 使用进程内滚动数据，Halo 重启或插件重载后统计重新开始。
- 回滚：在 Console 停用后卸载该插件。
