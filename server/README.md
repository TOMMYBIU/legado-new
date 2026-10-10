# 阅读 Archive 同步服务端

给 App 的「联网同步」提供账号与数据存储能力。零第三方依赖，只需要 Node.js 18 以上。

## 启动

```bash
cd server
node server.js
```

首次启动会自动生成 `config.json`（含随机 `secret`）与 `data/` 目录，监听 `0.0.0.0:8765`。

用环境变量可以临时覆盖配置：`SYNC_PORT`、`SYNC_HOST`、`SYNC_DATA_DIR`、`SYNC_SECRET`、`SYNC_ALLOW_REGISTER`。

## 配置项

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `host` | `0.0.0.0` | 监听地址，只想本机访问改成 `127.0.0.1` |
| `port` | `8765` | 监听端口 |
| `dataDir` | `./data` | 数据目录 |
| `secret` | 首次启动随机生成 | 签发 token 的密钥，改动会让所有已登录设备失效 |
| `tokenTtlDays` | `30` | token 有效天数 |
| `allowRegister` | `true` | 是否允许注册新账号，账号建好后可以改成 `false` |
| `maxSnapshotBytes` | `67108864` | 单次上传快照大小上限（64 MB） |
| `historyKeep` | `3` | 每个同步类型保留的历史版本数（被覆盖前的旧快照） |
| `namespaces` | `sources/shelf/settings/bookmarks` | 允许的同步类型 |

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/health` | 存活检查，返回支持的同步类型与注册开关 |
| POST | `/v1/auth/register` | `{username, password}` → `{username, token, expiresAt}` |
| POST | `/v1/auth/login` | `{username, password}` → `{username, token, expiresAt}` |
| GET | `/v1/manifest` | 各同步类型的 `updatedAt / hash / size / deviceId` |
| GET | `/v1/snapshot/{ns}` | 下载快照（二进制），时间戳/哈希在响应头 |
| PUT | `/v1/snapshot/{ns}` | 上传快照，请求头 `X-Sync-Device` 记录来源设备 |
| DELETE | `/v1/snapshot/{ns}` | 删除该类型的服务端快照 |

除 `health` 与两个 `auth` 接口外，其余都需要 `Authorization: Bearer <token>`。

## 部署提示

- 反代示例（nginx）：

  ```nginx
  location / {
      proxy_pass http://127.0.0.1:8765;
      proxy_set_header Host $host;
      client_max_body_size 128m;   # 要大于 maxSnapshotBytes
  }
  ```

- 建议放在 HTTPS 之后；HTTP 也能用，但密码与快照是明文传输。
- 数据直接落盘在 `data/`，备份该目录即可。密码使用 scrypt 加盐哈希存储，快照本体不加密，敏感环境请自行对磁盘加密。

## 部署：NAS 跑服务 + frp 暴露（自签证书）

适合「数据留在家里 NAS、云服务器只做入口」的场景，不需要域名、不需要备案：

```
App ──HTTPS──> 云服务器:8443 (frps) ──frp 隧道──> NAS 127.0.0.1:8765 (本服务, TLS)
```

TLS 由本服务自己终结（`tlsCert` / `tlsKey`），所以云服务器上不用装 nginx。

### 1. NAS 上准备目录

```bash
cd <你的目录>/server
cp config.example.json config.json
```

### 2. 生成自签证书

```bash
bash deploy/gen-cert.sh 116.62.121.29    # 参数填你的公网入口 IP
```

### 3. 改 `config.json`

```json
{
  "port": 8765,
  "tlsCert": "./certs/cert.pem",
  "tlsKey": "./certs/key.pem"
}
```

其余保持默认。账号注册完再把 `allowRegister` 改成 `false`。

### 4. 启动

```bash
docker compose up -d
docker logs -f legado-sync
```

日志应出现 `同步服务已启动: https://0.0.0.0:8765`。本机验证：

```bash
curl -k https://127.0.0.1:8765/v1/health
```

### 5. 云服务器上开通映射

在 NAS 的 frpc 配置里追加 `deploy/frpc-proxy.toml` 里的那一段，然后重启 frpc。

两个前提：

- frps 若配置了 `allowPorts`，要包含 `8443`
- 腾讯云安全组放行 `8443/TCP`（**只放这一个，8765 不要对外**）

### 6. App 端

「我的 → 联网同步」里服务器地址填 `https://116.62.121.29:8443`，注册并登录，先点一次「推送本机」建立同步基线。

### 排错

| 现象 | 检查 |
| --- | --- |
| 云端 `curl -k https://IP:8443/v1/health` 不通 | 看 frps 日志、安全组、`allowPorts` |
| App 提示连接失败 | 地址必须带 `https://`，端口要写 8443 |
| 日志报证书不存在 | `deploy/gen-cert.sh` 是否在 `server/` 下执行过，`config.json` 路径是否是 `./certs/...` |
| 所有设备突然掉登录 | `config.json` 里的 `secret` 变了，恢复备份或重新登录 |

### 安全提醒

- 公网只开 8443，且必须走 TLS；明文 HTTP 会泄露账号密码与快照内容
- `secret` 与 `data/` 一起备份；`config.json`、`data/` 都已加入 `.gitignore`
- 以后想换成域名（例如走 Cloudflare），本服务不需要改动，客户端地址换成 `https://你的域名` 即可
