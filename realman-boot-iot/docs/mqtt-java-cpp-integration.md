# MQTT 联调对接文档（ 服务端 ↔ 客户端 ）

本文档基于当前仓库实现整理：**服务端**为 Spring Boot（`realman-boot-iot-biz`），**设备端**以 客户端 实现 MQTT 客户端。所有业务
Topic 定义见 `DeviceConstant.MqttTopic`，JSON 模型见 `MqttMessageModel`。

---

## 1. 连接与鉴权

| 项             | 说明                                                                           |
|---------------|------------------------------------------------------------------------------|
| Broker        | 与部署环境一致，默认参考配置项 `mqtt.broker.url`（如 `tcp://emqx:1883`）                       |
| 设备连接          | `clientId` / `username` 一般为 **`deviceCode`**（与平台注册一致）                        |
| 连接密码          | 与 EMQX HTTP 鉴权约定一致；常见为 **`MD5(deviceCode)`**（以你们 `/internal/mqtt/auth` 实现为准） |
| 平台连接          | 固定账号如 `iot-platform-server`（见 `MqttConfig`），用于订阅全量业务 Topic                   |
| QoS           | 业务消息建议使用 **QoS 1**（与 `mqtt.broker.qos` 一致）                                   |
| Clean Session | 平台侧为 `false`，便于断线重连后补收 QoS1 消息                                               |

**客户端 侧**：使用 Paho、mosquitto 或自研客户端均可；需支持 UTF-8 Payload、QoS1、自动重连。

---

## 2. Payload 加密（必接）

除 **EMQX `$SYS/...` 系统主题** 与下文 **「原始上报 Topic」** 外，凡走 `device/{deviceCode}/...` 与
`master/{controllerCode}/...` 标准业务 Topic 的负载，平台与设备均按 **`CommandEncryptService`** 约定加解密。

### 2.1 算法与格式

| 步骤   | 说明                                                    |
|------|-------------------------------------------------------|
| AES  | **AES-256-CBC**，填充 **PKCS5/PKCS7**（块大小 16）            |
| 密钥   | `key = SHA256( deviceCode 的 UTF-8 字节 )` 取 **前 32 字节** |
| IV   | 每条消息 **随机 16 字节**                                     |
| 传输格式 | `{ivHex 共32位十六进制小写}:{Base64(密文)}`                     |

解密端：若整串 **不符合** `^[0-9a-fA-F]{32}:.+`，则 Java 端视为 **明文 JSON**（仅当 `device.encrypt.enabled=false` 调试时使用）。

### 2.2 设备端 实现要点

1. 使用 OpenSSL / mbedTLS：`SHA256`、`AES-256-CBC`、`EVP_BytesToKey` 或手动取 SHA256 前 32 字节作 key。
2. 加密后拼接字符串：`sprintf` / `std::ostringstream` 生成 `ivHex + ":" + base64Cipher`。
3. **下行**：先 **解密** 再解析 JSON。
4. **上行**：先序列化 JSON，再 **加密** 再 `publish`。
5. `deviceCode` 必须与连接鉴权及 Topic 中的编码 **完全一致**（主控用 `controllerCode`，机器人用 `robotCode`）。

---

## 3. Topic 总览（方向约定）

- **下行**：平台 → 设备，设备需 **订阅**。
- **上行**：设备 → 平台，设备需 **发布**；平台在 `MqttConfig` 中已订阅对应通配符。
- **`%s` / `{code}`**：替换为对应设备的 **`deviceCode`**（机器人或主控在平台注册编码）。

### 3.1 机器人（`device_type = 1`）

| 方向 | Topic 模板                                     | 说明                                                                  | 明文模型（加密前 JSON）                                                                     |
|----|----------------------------------------------|---------------------------------------------------------------------|------------------------------------------------------------------------------------|
| 上行 | `device/{deviceCode}/status/report`          | 状态/心跳类上报                                                            | `MqttMessageModel.StatusReport`                                                    |
| 上行 | `device/{deviceCode}/config/ack`             | 配置同步结果                                                              | `ConfigAck`                                                                        |
| 上行 | `device/{deviceCode}/command/{cmd}/ack`      | 指令执行确认；`{cmd}` 与下行命令段一致，如 `restart`、`emergency-stop`、`stop-control` | `RestartAck` / `EmergencyStopAck` 等（字段均含 `commandId`,`code`,`message`,`timestamp`） |
| 上行 | `device/{deviceCode}/ota/progress`           | OTA 进度                                                              | `OtaProgress`                                                                      |
| 上行 | `device/{deviceCode}/log/operation`          | 操作日志                                                                | `OperationLogReport`                                                               |
| 上行 | `device/{deviceCode}/camera/stream/ack`      | 摄像头流地址应答                                                            | `CameraStreamResponse`                                                             |
| 上行 | `device/{deviceCode}/slam/upload/request`    | 请求上传地图                                                              | `SlamUploadRequest`                                                                |
| 上行 | `device/{deviceCode}/slam/upload/complete`   | 上传完成                                                                | `SlamUploadComplete`                                                               |
| 上行 | `device/{deviceCode}/slam/sync/ack`          | 同步结果                                                                | `SlamSyncAck`                                                                      |
| 上行 | `device/{deviceCode}/ext-params/request`     | 请求外部系统服务参数（如 STS 临时凭证）                                              | `ExtParamsRequest`                                                                 |
| 下行 | `device/{deviceCode}/config/push`            | 下发配置                                                                | `ConfigPush`                                                                       |
| 下行 | `device/{deviceCode}/command/restart`        | 远程重启                                                                | `RemoteRestartCommand`                                                             |
| 下行 | `device/{deviceCode}/command/emergency-stop` | 紧急停机                                                                | `EmergencyStopCommand`                                                             |
| 下行 | `device/{deviceCode}/command/stop-control`   | 停止遥操等（平台实现里与 `RobotAssignCommand` 同结构）                              | `RobotAssignCommand`                                                               |
| 下行 | `device/{deviceCode}/ota/notify`             | OTA 通知                                                              | `OtaNotify`                                                                        |
| 下行 | `device/{deviceCode}/camera/stream/query`    | 查询摄像头流                                                              | `CameraStreamQuery`                                                                |
| 下行 | `device/{deviceCode}/slam/upload/permit`     | 上传许可                                                                | `SlamUploadPermit`                                                                 |
| 下行 | `device/{deviceCode}/slam/sync/command`      | 同步地图指令                                                              | `SlamSyncCommand`                                                                  |
| 下行 | `device/{deviceCode}/ext-params/ack`         | 平台响应外部系统服务参数                                                        | `ExtParamsResponse`                                                                |

**订阅建议（机器人最小集）**：`device/{deviceCode}/config/push`、`.../command/+`、`.../ota/notify`、`.../camera/stream/query`、
`.../slam/upload/permit`、`.../slam/sync/command`、`.../ext-params/ack`（可按能力裁剪）。

### 3.2 主控（`device_type = 2`）

| 方向 | Topic 模板                                                 | 说明                                                               | 明文模型                                                                  |
|----|----------------------------------------------------------|------------------------------------------------------------------|-----------------------------------------------------------------------|
| 上行 | `master/{controllerCode}/command/{cmd}/ack`              | 主控指令 ACK，`{cmd}` 如 `force-feedback`、`sport-speed`、`stop-control` | JSON：`commandId`,`code`,`message`,…（与 `MasterCommandAckHandler` 解析一致） |
| 上行 | `master/{controllerCode}/teleop/associated-device/ack`   | 关联机器人查询响应                                                        | `AssociatedDeviceResponse`                                            |
| 下行 | `master/{controllerCode}/teleop/associated-device/query` | 查询当前关联设备                                                         | `AssociatedDeviceQuery`                                               |
| 下行 | `master/{controllerCode}/teleop/robot/assign`            | 指派遥操目标机器人                                                        | `RobotAssignCommand`                                                  |
| 下行 | `master/{controllerCode}/command/sport-speed`            | 运动速度等级                                                           | `MasterSportSpeedCommand`                                             |
| 下行 | `master/{controllerCode}/command/force-feedback`         | 力反馈等级                                                            | `MasterForceFeedbackCommand`                                          |
| 下行 | `master/{controllerCode}/command/stop-control`           | 停止遥操（机器人侧同路径前缀为 `master/{robotCode}/...`，见平台代码）                  | `RobotAssignCommand`                                                  |

**订阅建议（主控）**：`master/{controllerCode}/teleop/associated-device/query`、
`master/{controllerCode}/teleop/robot/assign`、`master/{controllerCode}/command/+`。

---

## 4. JSON 示例（加密前明文）

以下可直接作为 客户端 侧序列化参考；字段名需与 **Java 驼峰** 一致（Jackson 默认）。

### 4.1 机器人

**上行 `device/{code}/status/report`**

```json
{
  "temperature": 25.5,
  "humidity": 60.0,
  "batteryLevel": 88.0,
  "signalStrength": -65,
  "runStatus": 1,
  "longitude": 116.397128,
  "latitude": 39.916527,
  "timestamp": 1710000000000,
  "extra": {
    "custom": "value"
  }
}
```

**下行 `device/{code}/config/push` → 上行 `.../config/ack`**

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "params": {
    "maxSpeed": "1.2",
    "workMode": "auto"
  },
  "timestamp": 1710000000000
}
```

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "code": 0,
  "message": null,
  "timestamp": 1710000000100
}
```

**下行 `.../command/restart` → 上行 `.../command/restart/ack`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "reason": "运维重启",
  "timestamp": 1710000000000
}
```

```json
{
  "commandId": "req_084ecb37bbd8",
  "message": "accepted",
  "code": 0,
  "timestamp": 1710000000100
}
```

**下行 `.../command/emergency-stop` → 上行 `.../command/emergency-stop/ack`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "reason": "急停",
  "timestamp": 1710000000000
}
```

```json
{
  "commandId": "req_084ecb37bbd8",
  "message": null,
  "code": 0,
  "timestamp": 1710000000100
}
```

**下行 `.../ota/notify` → 上行 `.../ota/progress`**

```json
{
  "taskId": "t1",
  "recordId": "r1",
  "firmwareId": "f1",
  "version": "1.0.2",
  "downloadUrl": "https://...",
  "fileMd5": "req_084ecb37bbd8”,"
  fileSize
  ":1048576,"
  forceUpgrade
  ":0,"
  timestamp
  ":1710000000000}
```

```json
{
  "taskId": "t1",
  "recordId": "r1",
  "failReason": null,
  "newVersion": null,
  "status": 3,
  "progress": 45,
  "downloadedBytes": 471859,
  "timestamp": 1710000001000
}
```

**下行 `.../camera/stream/query` → 上行 `.../camera/stream/ack`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "cameraIndex": null,
  "timestamp": 1710000000000
}
```

```json
{
  "commandId": "req_084ecb37bbd8",
  "code": 0,
  "message": null,
  "cameras": [
    {
      "cameraIndex": 0,
      "cameraName": "front",
      "streamUrl": "rtsp://...",
      "streamType": "rtsp"
    }
  ],
  "timestamp": 1710000000500
}
```

**上行 `.../log/operation`**

```json
{
  "operationType": "LOCAL",
  "operationDesc": "按键急停",
  "operationDetail": null,
  "operationResult": "SUCCESS",
  "operationTime": 1710000000000
}
```

### 4.2 SLAM 地图上传与同步

SLAM 涉及两条独立子流程，均走 **`device/{deviceCode}/slam/...`** Topic，Payload 经 `CommandEncryptService` 加解密。

---

#### 4.2.1 地图上传流程

```
机器人                                   平台
  │                                        │
  │── slam/upload/request ────────────────>│  1. 机器人告知平台：准备上传地图，附文件元信息
  │<── slam/upload/permit ─────────────────│  2. 平台响应预签名上传地址（PUT URL）
  │                                        │
  │   （机器人使用 putUrl 直传 OSS/MinIO）  │
  │                                        │
  │── slam/upload/complete ───────────────>│  3. 机器人通知平台：上传已完成
```

**第 1 步：上行 `device/{deviceCode}/slam/upload/request`**

```json
{
  "requestId": "req-20240101-001",
  "mapName": "warehouse_floor1",
  "mapVersion": "v2.1",
  "md5": "d41d8cd98f00b204e9800998ecf8427e",
  "size": 2097152,
  "ext": "pgm",
  "timestamp": 1710000000000
}
```

| 字段           | 说明                                       |
|--------------|------------------------------------------|
| `requestId`  | 本次上传请求 ID，与后续 `permit` / `complete` 三步对应 |
| `mapName`    | 地图名称（人可读）                                |
| `mapVersion` | 地图版本号（可选）                                |
| `md5`        | 文件 MD5，平台用于完整性校验                         |
| `size`       | 文件字节数                                    |
| `ext`        | 文件扩展名（不含点），如 `pgm`、`zip`                 |

**第 2 步：下行 `device/{deviceCode}/slam/upload/permit`**

```json
{
  "requestId": "req-20240101-001",
  "mapId": "map-uuid-0001",
  "objectKey": "slam/ROBOT_001/map-uuid-0001.pgm",
  "putUrl": "https://oss.example.com/slam/ROBOT_001/map-uuid-0001.pgm?X-Amz-Signature=...",
  "expireAt": 1710003600000,
  "timestamp": 1710000000200
}
```

| 字段          | 说明                               |
|-------------|----------------------------------|
| `requestId` | 与请求一致                            |
| `mapId`     | 平台生成的地图 ID，后续引用地图均用此字段           |
| `objectKey` | 存储 Object Key（用于 complete 阶段核对）  |
| `putUrl`    | 预签名 PUT URL，机器人直接 HTTP PUT 上传文件体 |
| `expireAt`  | PUT URL 过期时间（毫秒时间戳），超时需重新申请      |

> **客户端行为**：收到 `permit` 后，用 `putUrl` 做 HTTP PUT 上传裸文件（Content-Type 与 OSS 约定一致），上传成功再发
`upload/complete`。

**第 3 步：上行 `device/{deviceCode}/slam/upload/complete`**

成功：

```json
{
  "requestId": "req-20240101-001",
  "mapId": "map-uuid-0001",
  "objectKey": "slam/ROBOT_001/map-uuid-0001.pgm",
  "md5": "d41d8cd98f00b204e9800998ecf8427e",
  "size": 2097152,
  "code": 0,
  "message": null,
  "timestamp": 1710000050000
}
```

失败：

```json
{
  "requestId": "req-20240101-001",
  "mapId": "map-uuid-0001",
  "objectKey": "slam/ROBOT_001/map-uuid-0001.pgm",
  "md5": null,
  "size": null,
  "code": 1,
  "message": "PUT 上传超时",
  "timestamp": 1710000050000
}
```

| 字段        | 说明                  |
|-----------|---------------------|
| `code`    | `0` = 成功，非 `0` = 失败 |
| `message` | 失败原因（成功时为 `null`）   |

---

#### 4.2.2 地图同步流程

```
平台                                    目标机器人
  │                                        │
  │── slam/sync/command ──────────────────>│  1. 平台指示机器人从 OSS 拉取地图
  │<── slam/sync/ack ──────────────────────│  2. 机器人同步完成（或失败）后回传结果
```

**第 1 步：下行 `device/{deviceCode}/slam/sync/command`**

```json
{
  "commandId": "task-sync-0001",
  "bindingId": "binding-uuid-0001",
  "slamMapId": "map-uuid-0001",
  "sourceRobotCode": "ROBOT_001",
  "objectKey": "slam/ROBOT_001/map-uuid-0001.pgm",
  "getUrl": "https://oss.example.com/slam/ROBOT_001/map-uuid-0001.pgm?X-Amz-Signature=...",
  "md5": "d41d8cd98f00b204e9800998ecf8427e",
  "size": 2097152,
  "timestamp": 1710000060000
}
```

| 字段                | 说明                            |
|-------------------|-------------------------------|
| `commandId`       | 同步任务 ID，与 ACK 对应              |
| `bindingId`       | 同步绑定关系 ID（多机共享场景使用）           |
| `slamMapId`       | 地图 ID（与上传阶段一致）                |
| `sourceRobotCode` | 地图来源机器人编码                     |
| `objectKey`       | 存储 Object Key                 |
| `getUrl`          | 预签名 GET URL，机器人直接 HTTP GET 下载 |
| `md5`             | 平台已校验的文件 MD5，机器人下载后需对比        |

> **客户端行为**：收到指令后用 `getUrl` HTTP GET 下载地图文件，校验 MD5，加载完毕后发 `slam/sync/ack`。

**第 2 步：上行 `device/{deviceCode}/slam/sync/ack`**

成功：

```json
{
  "commandId": "task-sync-0001",
  "bindingId": "binding-uuid-0001",
  "slamMapId": "map-uuid-0001",
  "code": 0,
  "message": null,
  "timestamp": 1710000120000
}
```

失败：

```json
{
  "commandId": "task-sync-0001",
  "bindingId": "binding-uuid-0001",
  "slamMapId": "map-uuid-0001",
  "code": 2,
  "message": "MD5 校验不一致",
  "timestamp": 1710000120000
}
```

| 字段     | 说明                                                       |
|--------|----------------------------------------------------------|
| `code` | `0` = 成功，非 `0` = 失败（建议定义：`1`=下载失败 `2`=MD5 校验失败 `3`=加载失败） |

---

### 4.3 外部系统服务参数（STS 凭证）

设备在需要访问对象存储前，向平台请求 STS 临时凭证。所有设备共享同一套参数；平台读取 Redis 缓存（TTL 与凭证过期时间对齐），缓存未命中时自动降级查库。

**上行 `device/{code}/ext-params/request`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "sourceSystem": "DEW"
}
```

**下行 `device/{code}/ext-params/ack`（成功）**

```json
{
  "commandId": "req_084ecb37bbd8",
  "code": 0,
  "endpoint": "sts.cn-beijing.aliyuncs.com",
  "bucket": "embodied-data",
  "bjExpiration": "2026-02-25 17:28:15",
  "utcExpiration": "2026-02-25T09:28:15Z",
  "accessKeyId": "STS.NYx3uEBnMWqC3ogAa14JAFM6y",
  "accessKeySecret": "Ai36sQjvJgoXoyusBkNJCYjAKup9Vy7g7JW2EsQj7v1h",
  "securityToken": "CAISxwJ1q6Ft5B2yfSjIr5rNeM..."
}
```

**下行 `device/{code}/ext-params/ack`（失败，缓存与库中均无数据）**

```json
{
  "commandId": "req_084ecb37bbd8",
  "code": 400,
  "message": "暂无可用的外部服务参数，请稍后重试"
}
```

**客户端行为**：

- `commandId` 使用每次请求唯一的字符串（UUID 或时间戳+随机数），响应中原样返回用于对账。
- `sourceSystem` 可缺省，平台使用默认配置值（当前为 `DEW`）。
- 比对 `bjExpiration`，在过期前（建议提前 5 分钟）主动重新请求刷新凭证。
- 收到 `code=400` 时等待后重试（外部系统尚未向平台推送参数）。

---

### 4.4 主控

**下行 `master/{code}/teleop/associated-device/query` → 上行 `master/{code}/teleop/associated-device/ack`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "operatorId": "op001",
  "loginLogId": null,
  "timestamp": 1710000000000
}
```

```json
{
  "commandId": "req_084ecb37bbd8",
  "code": 0,
  "message": null,
  "operatorId": "op001",
  "loginLogId": null,
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "robotId": null,
  "robotCode": "ROBOT_001",
  "timestamp": 1710000000800
}
```

**下行 `master/{code}/teleop/robot/assign`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "robotCode": "ROBOT_001",
  "workOrderId": "WO-10086",
  "timestamp": 1710000000000
}
```

**下行 `master/{code}/command/force-feedback`**

```json
{
  "commandId": "req_084ecb37bbd8",
  "armLevel": 3,
  "gripperLevel": 2,
  "timestamp": 1710000000000
}
```

**下行 `master/{code}/command/sport-speed`**

```json
{
  "commandId": "req_084ecb37bbd8“,"
  moveSpeedLevel
  ":2,"
  liftSpeedLevel
  ":1,"
  timestamp
  ":1710000000000}
```

**停止遥操（平台当前实现）**  
主控侧下行 Topic：`master/{controllerCode}/command/stop-control`；机器人侧：`device/{robotCode}/command/stop-control`
。Payload 均为 **`RobotAssignCommand`** JSON（含 `workOrderId` 等），请以线上 `IotDeviceServiceImpl#stopTeleop` 为准。

---

## 5. 原始上报 Topic（当前实现为明文 JSON）

平台订阅：

| Topic 通配符                       | 说明                                                    |
|---------------------------------|-------------------------------------------------------|
| `{deviceCode}/master/cmd`       | 主控原始上报                                                |
| `{deviceCode}/master/states`    | 主控状态（会进 `robotSlaveStatusHandler.handleMasterStatus`） |
| `{deviceCode}/master/rtsp/ctrl` | 主控 RTSP 相关                                            |
| `{deviceCode}/slave/cmd`        | 机器人原始上报                                               |
| `{deviceCode}/slave/states`     | 机器人状态（`handle`）                                       |

**客户端 侧**：按设备角色选择 `设备码/master/...` 或 `设备码/slave/...`；Payload 为 **UTF-8 明文 JSON**（不经
`CommandEncryptService` 包一层）。具体字段与业务确认后再定 schema。

---

## 6. EMQX 系统事件（平台侧）

| Topic                                   | 说明    |
|-----------------------------------------|-------|
| `$SYS/brokers/+/clients/+/connected`    | 客户端上线 |
| `$SYS/brokers/+/clients/+/disconnected` | 客户端下线 |

设备 **无需订阅**；由平台解析 clientId 等设备标识做上下线状态。客户端 客户端仅需保证 **连接时 clientId 可被平台识别为
deviceCode**。

---

## 7. 联调检查清单

1. **加密**：用同一 `deviceCode` 对样例 JSON 加解密，与 Java `CommandEncryptService` 互测一条往返。
2. **Topic**：逐条核对本节表格与 `DeviceConstant.MqttTopic`，注意 **`master/`** 与 **`device/`** 前缀不可混用。
3. **commandId**：所有 Query/Command 与 Ack/Response **必须**携带相同 `commandId`（字符串 UUID）。
4. **摄像头**：机器人应答必须使用 **`device/{code}/camera/stream/ack`**（与
   `DeviceConstant.MqttTopic.CAMERA_STREAM_RESPONSE`、平台订阅、分发器一致）。
5. **主控关联设备**：应答 Topic 为 **`master/{code}/teleop/associated-device/ack`**。
6. **外部服务参数**：订阅 `device/{code}/ext-params/ack`，发起请求时 `commandId` 须唯一；根据 `bjExpiration` 在过期前主动刷新，收到
   `code=400` 稍后重试。
7. **调试**：可将 `device.encrypt.enabled=false` 用明文 JSON 抓包；上线前务必恢复为加密。

---

## 8. Java 代码索引

| 内容       | 位置                              |
|----------|---------------------------------|
| Topic 常量 | `DeviceConstant.MqttTopic`      |
| JSON 模型  | `MqttMessageModel`              |
| 加解密      | `CommandEncryptService`         |
| 平台订阅列表   | `MqttConfig`                    |
| Topic 路由 | `MqttMessageDispatcher`         |
| 下行发布     | `MqttPublisher.publishToDevice` |

---

## 9. 关联脚本与补充说明

- **C 端（libmosquitto + OpenSSL）联调程序**：`scripts/c/`（见 [scripts/c/README.md](../scripts/c/README.md)）
- 关联设备 Query/Ack 的 Python 联调脚本：`scripts/mqtt_associated_device_demo.py`
- 简要说明：`docs/mqtt-associated-device-debug.md`

若后续协议变更，请以 **`DeviceConstant` + `MqttMessageModel` + `MqttConfig`** 三处源码为准，并同步更新本文档。
