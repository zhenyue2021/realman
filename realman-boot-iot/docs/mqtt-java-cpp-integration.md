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
| 上行 | `device/{deviceCode}/slam/states`            | 上报 SLAM 工作模式及当前位姿（IdleMode 仅有模式，其余模式同时含点位）                            | `SlamStates`                                                                       |
| 上行 | `device/{deviceCode}/slam/ack`               | 响应平台 `slam/request` 指令；含 `sequence`/`total` 支持分步多次响应                  | `SlamAck`                                                                          |
| 上行 | `device/{deviceCode}/ext-params/request`     | 请求外部系统服务参数（如 STS 临时凭证）                                              | `ExtParamsRequest`                                                                 |
| 下行 | `device/{deviceCode}/config/push`            | 下发配置                                                                | `ConfigPush`                                                                       |
| 下行 | `device/{deviceCode}/command/restart`        | 远程重启                                                                | `RemoteRestartCommand`                                                             |
| 下行 | `device/{deviceCode}/command/emergency-stop` | 紧急停机                                                                | `EmergencyStopCommand`                                                             |
| 下行 | `device/{deviceCode}/command/stop-control`   | 停止遥操等（平台实现里与 `RobotAssignCommand` 同结构）                              | `RobotAssignCommand`                                                               |
| 下行 | `device/{deviceCode}/ota/notify`             | OTA 通知                                                              | `OtaNotify`                                                                        |
| 下行 | `device/{deviceCode}/camera/stream/query`    | 查询摄像头流                                                              | `CameraStreamQuery`                                                                |
| 下行 | `device/{deviceCode}/slam/request`           | 向设备发送建图/定位/导航指令，`function` 字段区分具体功能                                  | `SlamRequest`                                                                      |
| 下行 | `device/{deviceCode}/ext-params/ack`         | 平台响应外部系统服务参数                                                        | `ExtParamsResponse`                                                                |
| 下行 | `device/{deviceCode}/webrtc/request`                | 下发 WebRTC 统一指令，payload `command` 字段区分 start/stop；start 时平台等待 ACK 最多 5 秒 | `WebRtcCommand`                                                                    |
| 上行 | `device/{deviceCode}/webrtc/ack`            | WebRTC 指令 ACK（start/stop 统一 Topic）；start ACK `success=false` 或超时均视为开启失败 | `WebRtcAck`                                                                        |

**订阅建议（机器人最小集）**：`device/{deviceCode}/config/push`、`.../command/+`、`.../ota/notify`、`.../camera/stream/query`、
`.../slam/request`、`.../ext-params/ack`、`device/{deviceCode}/webrtc/request`（可按能力裁剪）。

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
   "fileMd5": "req_084ecb37bbd8",
   "fileSize":1048576,
   "forceUpgrade":0,
   "timestamp":1710000000000
}
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

### 4.2 SLAM 建图、定位与导航

SLAM 功能通过三条 Topic 实现双向交互，均走 **`device/{deviceCode}/slam/...`** 前缀，Payload 经 `CommandEncryptService` 加解密。

---

**上行 `device/{deviceCode}/slam/states`**（设备主动上报）

上报当前 SLAM 工作模式及机器人位姿。`IdleMode` 下仅有模式字段，无点位；`MappingAndLocalization` 与 `LocalizationAndNavigation` 模式下同时包含 `current_pose`。

```json
{
  "slam_nav_mode": "MappingAndLocalization",
  "current_pose": {
    "pixel_x": 2,
    "pixel_y": 3,
    "yaw": 1.57
  }
}
```

**SLAM 地图模式枚举（`slam_nav_mode`）**

| 值 | 说明 |
|---|------|
| `IdleMode` | 空闲模式，仅有状态，无点位 |
| `MappingAndLocalization` | 建图定位模式，含点位 |
| `LocalizationAndNavigation` | 定位导航模式，含点位 |

---

**下行 `device/{deviceCode}/slam/request`**（平台向设备下发指令）

```json
{
  "commandId": "req_084ecb37bbd8",
  "function": "GetCurrentMap",
  "params": {
    "target_mode": "MappingAndLocalization"
  }
}
```

| 字段 | 说明 |
|---|------|
| `commandId` | 唯一指令 ID，ACK 中原样返回 |
| `function` | 功能代码（见下表） |
| `params` | 功能参数，随 `function` 不同而变化 |

**function 功能代码**

| 值 | 说明 |
|---|------|
| `SwitchMode` | 切换模式（目标模式通过 `params.target_mode` 传入） |
| `GetCurrentMap` | 获取当前地图（返回地图文件或栅格地图） |
| `SaveMap` | 保存地图（`MappingAndLocalization` 模式下可用） |
| `SinglePointNavigation` | 单点导航（需上送目标点位及像素坐标） |
| `MultiWaypointNavigation` | 多点顺序导航（逐点执行，非连续曲线跟踪） |
| `SetInitialPose` | 重定位（设置初始位姿，用于定位恢复，页面手动触发，需上送目标点位及像素坐标） |

---

**上行 `device/{deviceCode}/slam/ack`**（设备响应平台请求）

部分功能（如 `GetCurrentMap`）需分多次响应，通过 `sequence` / `total` 字段标识进度；`sequence == total` 时为最终响应，后续不再发送。

第 1 次响应示例：

```json
{
  "commandId": "req_084ecb37bbd8",
  "function": "GetCurrentMap",
  "success": true,
  "code": 0,
  "message": "ok",
  "sequence": 1,
  "total": 2,
  "data": {}
}
```

最终响应示例（`sequence == total`）：

```json
{
  "commandId": "req_084ecb37bbd8",
  "function": "GetCurrentMap",
  "success": true,
  "code": 0,
  "message": "ok",
  "sequence": 2,
  "total": 2,
  "data": {}
}
```

| 字段 | 说明 |
|---|------|
| `commandId` | 与下行 `slam/request` 的 `commandId` 一致 |
| `function` | 与请求的 `function` 一致 |
| `success` | `true` = 本次响应成功；`false` = 失败 |
| `code` | `0` = 成功，非 `0` = 失败 |
| `sequence` | 当前第几次响应（从 1 开始） |
| `total` | 完成请求共需响应次数；`sequence == total` 时为最终响应 |
| `data` | 响应数据，字段随 `function` 而定 |

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
   "commandId": "req_084ecb37bbd8",
   "moveSpeedLevel": 2,
   "liftSpeedLevel": 1,
   "timestamp": 1710000000000
}
```

**停止遥操（平台当前实现）**  
主控侧下行 Topic：`master/{controllerCode}/command/stop-control`；机器人侧：`device/{robotCode}/command/stop-control`
。Payload 均为 **`RobotAssignCommand`** JSON（含 `workOrderId` 等），请以线上 `IotDeviceServiceImpl#stopTeleop` 为准。

---

### 4.5 WebRTC 遥操视频通话

WebRTC 指令复用标准 `device/{robotCode}/...` Topic 前缀，Payload 同样经 `CommandEncryptService` 加解密。
start 与 stop 合并为同一下行 Topic，通过 payload 中 `command` 字段区分。流程如下：

```
平台                                     机器人
  │                                        │
  │── device/{robotCode}/webrtc/request ─────────>│  1. 平台下发 WebRTC 开始指令（command="start"，含房间信息）
  │<── device/{robotCode}/webrtc/ack ─────│  2. 机器人响应（5 秒超时，超时视为失败）
  │                                        │
  │   （WebRTC P2P 连接建立，视频流传输）   │
  │                                        │
  │── device/{robotCode}/webrtc/request ─────────>│  3. 平台下发 WebRTC 停止指令（command="stop"，不等待 ACK）
  │<── device/{robotCode}/webrtc/ack ─────│  4. 机器人可选回应（platform 仅记日志）
```

> **注意**：`device/.../webrtc/...` Topic 中的 `{deviceCode}` 为**机器人**设备编码（`robotCode`），非主控编码。

**下行 `device/{robotCode}/webrtc/request`（command="start"）**

```json
{
  "command": "start",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "roomId": "1902887123456789012",
  "signalUrl": "192.168.1.100",
  "signalKey": "a3f8c2d1e4b5a6f7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f90a1b",
  "turnServers": [
    {
      "url": "turn:192.168.1.200:3478?transport=udp",
      "username": "turnuser",
      "password": "turnpass"
    }
  ],
  "stunServers": ["stun:stun.l.google.com:19302"],
  "timestamp": 1710000000000
}
```

| 字段            | 说明                                                                  |
|---------------|---------------------------------------------------------------------|
| `command`     | 固定为 `"start"`                                                      |
| `commandId`   | 唯一指令 ID（UUID），ACK 中原样返回                                           |
| `roomId`      | 平台为本次遥操分配的房间号                                                     |
| `signalUrl`   | 信令服务器 IP 地址                                                        |
| `signalKey`   | 信令服务器访问密钥（由 turn_router 调度接口返回）                          |
| `turnServers` | TURN 中继服务器列表，`url` 格式为 `turn:host:port?transport=udp`             |
| `stunServers` | STUN 服务器地址列表，格式为 `stun:host:port`                                  |
| `timestamp`   | 平台下发时间（毫秒）                                                         |

**上行 `device/{robotCode}/webrtc/ack`（成功）**

```json
{
  "command": "start",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "success": true,
  "message": null,
  "timestamp": 1710000000500
}
```

**上行 `device/{robotCode}/webrtc/ack`（失败）**

```json
{
  "command": "start",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "success": false,
  "message": "信令服务器连接超时",
  "timestamp": 1710000000500
}
```

| 字段          | 说明                                        |
|-------------|-------------------------------------------|
| `command`   | 回复的指令类型：`start` 或 `stop`               |
| `commandId` | 与下行指令一致                                  |
| `success`   | `true` = 成功建立 WebRTC；`false` = 失败，平台中断遥操 |
| `message`   | 失败原因（成功时为 `null`）                         |

**下行 `device/{robotCode}/webrtc/request`（command="stop"）**

```json
{
  "command": "stop",
  "commandId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "timestamp": 1710000060000
}
```

**上行 `device/{robotCode}/webrtc/ack`（可选，平台仅记日志）**

```json
{
  "command": "stop",
  "commandId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "success": true,
  "message": null,
  "timestamp": 1710000060200
}
```

**客户端行为**：

1. 订阅 `device/{robotCode}/webrtc/request`（单一 Topic，无通配符）。
2. 收到消息后解密，读取 `command` 字段判断指令类型。
3. `command="start"` 时：连接信令服务器建立 WebRTC 连接，完成后在 **5 秒内** 回复 ACK（`command="start"`）。
4. ACK 中 `success=false` 或未在 5 秒内应答，平台将终止本次遥操并提示操作员。
5. `command="stop"` 时：立即中断 WebRTC 连接，可选回复 ACK（`command="stop"`，平台仅记日志）。

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
7. **SLAM**：订阅 `device/{deviceCode}/slam/request`；主动周期上报 `slam/states`（含 `slam_nav_mode`，`IdleMode` 下无 `current_pose`）；回复 `slam/ack` 时 `commandId` 须与请求一致，多步响应需正确填写 `sequence`/`total`，`sequence == total` 时为最终响应。
8. **WebRTC**：订阅 `device/{robotCode}/webrtc/request`（单 Topic）；收到后读取 `command` 字段区分 start/stop；start ACK 中 `commandId` 须与下行指令一致，必须在 **5 秒**内回复，否则平台报超时错误；stop 为 fire-and-forget，stop ACK 可选。
9. **调试**：可将 `device.encrypt.enabled=false` 用明文 JSON 抓包；上线前务必恢复为加密。

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
