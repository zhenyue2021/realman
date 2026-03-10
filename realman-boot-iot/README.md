# IoT Device Management Module

## 模块结构

```
device-management/
├── device-api/       ← 对外 REST 接口层（Controller / DTO / VO）
├── device-biz/       ← 业务实现层（MQTT / OTA / Security / WebSocket / Scheduler）
├── device-start/     ← 主启动类 + application.yml
└── sql/init.sql      ← 数据库初始化脚本
```

## 设备鉴权架构

设备**无需登录**，鉴权在 MQTT 连接层完成：

```
设备端：clientId=deviceCode, username=deviceCode, password=deviceSecret
    ↓
EMQX HTTP Auth 插件
    ↓  POST /device-mgmt/internal/mqtt/auth
平台 MqttAuthController.auth()
    ↓
DeviceSecretService.validateSecret()（Redis缓存 → DB降级）
    ↓
allow / deny 返回给 EMQX
```

连接建立后，消息 Payload 使用 **Per-Device AES-256-CBC** 加密：
```
deviceAesKey = SHA256(masterKey + ":" + deviceSecret)[0..31]
密文格式: ivHex(32char) + ":" + Base64(AES密文)
```

## 快速启动

### 1. 环境依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | |
| MySQL | 8.0 | |
| Redis | 6+ | |
| EMQX | 5.x | 需开启 HTTP Auth 插件 |
| MinIO | 最新版 | OTA 固件存储 |
| XXL-Job Admin | 2.4.0 | 定时任务（可选） |

### 2. 数据库初始化

```bash
mysql -u root -p < sql/init.sql
```

### 3. 修改配置

编辑 `device-start/src/main/resources/application.yml`：
- 修改 MySQL 连接信息（用户名/密码）
- 修改 Redis 连接信息
- 修改 MQTT Broker 地址
- 修改 MinIO 地址和密钥
- 设置 `DEVICE_ENCRYPT_MASTER_KEY` 环境变量

### 4. EMQX HTTP Auth 配置

在 EMQX Dashboard 或 `emqx.conf` 中添加：

```
# 认证
authentication {
  backend   = http
  mechanism = password_based
  method    = post
  url       = http://平台IP:8085/device-mgmt/internal/mqtt/auth
  headers   { "Content-Type" = "application/json" }
  body      { clientid = "${clientid}", username = "${username}", password = "${password}", peerhost = "${peerhost}" }
}

# ACL
authorization {
  sources = [{
    type    = http
    method  = post
    url     = http://平台IP:8085/device-mgmt/internal/mqtt/acl
    headers { "Content-Type" = "application/json" }
    body    { clientid = "${clientid}", username = "${username}", topic = "${topic}", action = "${action}" }
  }]
}
```

### 5. 构建与启动

```bash
mvn clean package -DskipTests
java -DDEVICE_ENCRYPT_MASTER_KEY=your-32-bytes-master-key \
     -jar device-start/target/device-start-1.0.0.jar
```

### 6. 访问

- API: http://localhost:8085/device-mgmt/api/device/list
- Swagger UI: http://localhost:8085/device-mgmt/swagger-ui/index.html
- WebSocket: ws://localhost:8085/device-mgmt/ws/device/{deviceCode}

## 主要 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/device/add | 新增设备 |
| POST | /api/device/list | 分页查询设备 |
| POST | /api/device/{id}/config/sync | 参数设置并同步 |
| GET  | /api/device/{id}/monitor | 实时监控状态 |
| POST | /api/device/{id}/restart | 远程重启 |
| PUT  | /api/device/{id}/status/{s} | 禁用/启用 |
| POST | /api/device/{id}/secret/reset | 重置设备密钥 |
| POST | /api/ota/firmware/upload/chunk | 固件分片上传 |
| GET  | /api/ota/firmware/upload/chunks | 查询已上传分片 |
| POST | /api/ota/firmware/upload/merge | 合并发布固件 |
| POST | /api/ota/task/create | 创建升级任务 |
| POST | /api/ota/task/{id}/execute | 执行升级任务 |
| POST | /internal/mqtt/auth | EMQX Auth回调（内部） |
| POST | /internal/mqtt/acl | EMQX ACL回调（内部） |
