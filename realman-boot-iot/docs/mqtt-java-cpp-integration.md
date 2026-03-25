# MQTT 联调对接文档（ 服务端 ↔  客户端 ）

本文档基于当前仓库实现整理：**服务端**为 Spring Boot（`realman-boot-iot-biz`），**设备端**以 客户端 实现 MQTT 客户端。所有业务 Topic 定义见 `DeviceConstant.MqttTopic`，JSON 模型见 `MqttMessageModel`。

---

## 1. 连接与鉴权

| 项 | 说明 |
|----|------|
| Broker | 与部署环境一致，默认参考配置项 `mqtt.broker.url`（如 `tcp://emqx:1883`） |
| 设备连接 | `clientId` / `username` 一般为 **`deviceCode`**（与平台注册一致） |
| 连接密码 | 与 EMQX HTTP 鉴权约定一致；常见为 **`MD5(deviceCode)`**（以你们 `/internal/mqtt/auth` 实现为准） |
| 平台连接 | 固定账号如 `iot-platform-server`（见 `MqttConfig`），用于订阅全量业务 Topic |
| QoS | 业务消息建议使用 **QoS 1**（与 `mqtt.broker.qos` 一致） |
| Clean Session | 平台侧为 `false`，便于断线重连后补收 QoS1 消息 |

**客户端 侧**：使用 Paho、mosquitto 或自研客户端均可；需支持 UTF-8 Payload、QoS1、自动重连。

---

## 2. Payload 加密（必接）

除 **EMQX `$SYS/...` 系统主题** 与下文 **「原始上报 Topic」** 外，凡走 `device/{deviceCode}/...` 与 `master/{controllerCode}/...` 标准业务 Topic 的负载，平台与设备均按 **`CommandEncryptService`** 约定加解密。

### 2.1 算法与格式

| 步骤 | 说明 |
|------|------|
| AES | **AES-256-CBC**，填充 **PKCS5/PKCS7**（块大小 16） |
| 密钥 | `key = SHA256( deviceCode 的 UTF-8 字节 )` 取 **前 32 字节** |
| IV | 每条消息 **随机 16 字节** |
| 传输格式 | `{ivHex 共32位十六进制小写}:{Base64(密文)}` |

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

| 方向 | Topic 模板 | 说明 | 明文模型（加密前 JSON） |
|------|------------|------|-------------------------|
| 上行 | `device/{deviceCode}/status/report` | 状态/心跳类上报 | `MqttMessageModel.StatusReport` |
| 上行 | `device/{deviceCode}/config/ack` | 配置同步结果 | `ConfigAck` |
| 上行 | `device/{deviceCode}/command/{cmd}/ack` | 指令执行确认；`{cmd}` 与下行命令段一致，如 `restart`、`emergency-stop`、`stop-control` | `RestartAck` / `EmergencyStopAck` 等（字段均含 `commandId`,`code`,`message`,`timestamp`） |
| 上行 | `device/{deviceCode}/ota/progress` | OTA 进度 | `OtaProgress` |
| 上行 | `device/{deviceCode}/log/operation` | 操作日志 | `OperationLogReport` |
| 上行 | `device/{deviceCode}/camera/stream/ack` | 摄像头流地址应答 | `CameraStreamResponse` |
| 上行 | `device/{deviceCode}/slam/upload/request` | 请求上传地图 | `SlamUploadRequest` |
| 上行 | `device/{deviceCode}/slam/upload/complete` | 上传完成 | `SlamUploadComplete` |
| 上行 | `device/{deviceCode}/slam/sync/ack` | 同步结果 | `SlamSyncAck` |
| 下行 | `device/{deviceCode}/config/push` | 下发配置 | `ConfigPush` |
| 下行 | `device/{deviceCode}/command/restart` | 远程重启 | `RemoteRestartCommand` |
| 下行 | `device/{deviceCode}/command/emergency-stop` | 紧急停机 | `EmergencyStopCommand` |
| 下行 | `device/{deviceCode}/command/stop-control` | 停止遥操等（平台实现里与 `RobotAssignCommand` 同结构） | `RobotAssignCommand` |
| 下行 | `device/{deviceCode}/ota/notify` | OTA 通知 | `OtaNotify` |
| 下行 | `device/{deviceCode}/camera/stream/query` | 查询摄像头流 | `CameraStreamQuery` |
| 下行 | `device/{deviceCode}/slam/upload/permit` | 上传许可 | `SlamUploadPermit` |
| 下行 | `device/{deviceCode}/slam/sync/command` | 同步地图指令 | `SlamSyncCommand` |

**订阅建议（机器人最小集）**：`device/{deviceCode}/config/push`、`.../command/+`、`.../ota/notify`、`.../camera/stream/query`、`.../slam/upload/permit`、`.../slam/sync/command`（可按能力裁剪）。

### 3.2 主控（`device_type = 2`）

| 方向 | Topic 模板 | 说明 | 明文模型 |
|------|------------|------|----------|
| 上行 | `master/{controllerCode}/command/{cmd}/ack` | 主控指令 ACK，`{cmd}` 如 `force-feedback`、`sport-speed`、`stop-control` | JSON：`commandId`,`code`,`message`,…（与 `MasterCommandAckHandler` 解析一致） |
| 上行 | `master/{controllerCode}/teleop/associated-device/ack` | 关联机器人查询响应 | `AssociatedDeviceResponse` |
| 下行 | `master/{controllerCode}/teleop/associated-device/query` | 查询当前关联设备 | `AssociatedDeviceQuery` |
| 下行 | `master/{controllerCode}/teleop/robot/assign` | 指派遥操目标机器人 | `RobotAssignCommand` |
| 下行 | `master/{controllerCode}/command/sport-speed` | 运动速度等级 | `MasterSportSpeedCommand` |
| 下行 | `master/{controllerCode}/command/force-feedback` | 力反馈等级 | `MasterForceFeedbackCommand` |
| 下行 | `master/{controllerCode}/command/stop-control` | 停止遥操（机器人侧同路径前缀为 `master/{robotCode}/...`，见平台代码） | `RobotAssignCommand` |

**订阅建议（主控）**：`master/{controllerCode}/teleop/associated-device/query`、`master/{controllerCode}/teleop/robot/assign`、`master/{controllerCode}/command/+`。

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
  "extra": { "custom": "value" }
}
```

**下行 `device/{code}/config/push` → 上行 `.../config/ack`**

```json
{"commandId":"550e8400-e29b-41d4-a716-446655440000","params":{"maxSpeed":"1.2","workMode":"auto"},"timestamp":1710000000000}
```

```json
{"commandId":"550e8400-e29b-41d4-a716-446655440000","code":0,"message":null,"timestamp":1710000000100}
```

**下行 `.../command/restart` → 上行 `.../command/restart/ack`**

```json
{"commandId":"...","reason":"运维重启","timestamp":1710000000000}
```

```json
{"commandId":"...","message":"accepted","code":0,"timestamp":1710000000100}
```

**下行 `.../command/emergency-stop` → 上行 `.../command/emergency-stop/ack`**

```json
{"commandId":"...","reason":"急停","timestamp":1710000000000}
```

```json
{"commandId":"...","message":null,"code":0,"timestamp":1710000000100}
```

**下行 `.../ota/notify` → 上行 `.../ota/progress`**

```json
{"taskId":"t1","recordId":"r1","firmwareId":"f1","version":"1.0.2","downloadUrl":"https://...","fileMd5":"...","fileSize":1048576,"forceUpgrade":0,"timestamp":1710000000000}
```

```json
{"taskId":"t1","recordId":"r1","failReason":null,"newVersion":null,"status":3,"progress":45,"downloadedBytes":471859,"timestamp":1710000001000}
```

**下行 `.../camera/stream/query` → 上行 `.../camera/stream/ack`**

```json
{"commandId":"...","cameraIndex":null,"timestamp":1710000000000}
```

```json
{"commandId":"...","code":0,"message":null,"cameras":[{"cameraIndex":0,"cameraName":"front","streamUrl":"rtsp://...","streamType":"rtsp"}],"timestamp":1710000000500}
```

**上行 `.../log/operation`**

```json
{"operationType":"LOCAL","operationDesc":"按键急停","operationDetail":null,"operationResult":"SUCCESS","operationTime":1710000000000}
```

### 4.2 主控

**下行 `master/{code}/teleop/associated-device/query` → 上行 `.../teleop/associated-device/ack`**

```json
{"commandId":"...","operatorId":"op001","loginLogId":null,"timestamp":1710000000000}
```

```json
{"commandId":"...","code":0,"message":null,"operatorId":"op001","loginLogId":null,"macAddress":"AA:BB:CC:DD:EE:FF","robotId":null,"robotCode":"ROBOT_001","timestamp":1710000000800}
```

**下行 `master/{code}/teleop/robot/assign`**

```json
{"commandId":"...","robotCode":"ROBOT_001","workOrderId":"WO-10086","timestamp":1710000000000}
```

**下行 `master/{code}/command/force-feedback`**

```json
{"commandId":"...","armLevel":3,"gripperLevel":2,"timestamp":1710000000000}
```

**下行 `master/{code}/command/sport-speed`**

```json
{"commandId":"...","moveSpeedLevel":2,"liftSpeedLevel":1,"timestamp":1710000000000}
```

**停止遥操（平台当前实现）**  
主控侧下行 Topic：`master/{controllerCode}/command/stop-control`；机器人侧：`device/{robotCode}/command/stop-control`。Payload 均为 **`RobotAssignCommand`** JSON（含 `workOrderId` 等），请以线上 `IotDeviceServiceImpl#stopTeleop` 为准。

---

## 5. 原始上报 Topic（当前实现为明文 JSON）

平台订阅：

| Topic 通配符 | 说明 |
|--------------|------|
| `{deviceCode}/master/cmd` | 主控原始上报 |
| `{deviceCode}/master/states` | 主控状态（会进 `robotSlaveStatusHandler.handleMasterStatus`） |
| `{deviceCode}/master/rtsp/ctrl` | 主控 RTSP 相关 |
| `{deviceCode}/slave/cmd` | 机器人原始上报 |
| `{deviceCode}/slave/states` | 机器人状态（`handle`） |

**客户端 侧**：按设备角色选择 `设备码/master/...` 或 `设备码/slave/...`；Payload 为 **UTF-8 明文 JSON**（不经 `CommandEncryptService` 包一层）。具体字段与业务确认后再定 schema。

---

## 6. EMQX 系统事件（平台侧）

| Topic | 说明 |
|-------|------|
| `$SYS/brokers/+/clients/+/connected` | 客户端上线 |
| `$SYS/brokers/+/clients/+/disconnected` | 客户端下线 |

设备 **无需订阅**；由平台解析 clientId 等设备标识做上下线状态。客户端 客户端仅需保证 **连接时 clientId 可被平台识别为 deviceCode**。

---

## 7. 联调检查清单

1. **加密**：用同一 `deviceCode` 对样例 JSON 加解密，与 Java `CommandEncryptService` 互测一条往返。  
2. **Topic**：逐条核对本节表格与 `DeviceConstant.MqttTopic`，注意 **`master/`** 与 **`device/`** 前缀不可混用。  
3. **commandId**：所有 Query/Command 与 Ack/Response **必须**携带相同 `commandId`（字符串 UUID）。  
4. **摄像头**：机器人应答必须使用 **`device/{code}/camera/stream/ack`**（与 `DeviceConstant.MqttTopic.CAMERA_STREAM_RESPONSE`、平台订阅、分发器一致）。  
5. **主控关联设备**：应答 Topic 为 **`master/{code}/teleop/associated-device/ack`**。  
6. **调试**：可将 `device.encrypt.enabled=false` 用明文 JSON 抓包；上线前务必恢复为加密。

---

## 8. Java 代码索引

| 内容 | 位置 |
|------|------|
| Topic 常量 | `DeviceConstant.MqttTopic` |
| JSON 模型 | `MqttMessageModel` |
| 加解密 | `CommandEncryptService` |
| 平台订阅列表 | `MqttConfig` |
| Topic 路由 | `MqttMessageDispatcher` |
| 下行发布 | `MqttPublisher.publishToDevice` |

---

## 9. 关联脚本与补充说明

- **C 端（libmosquitto + OpenSSL）联调程序**：`scripts/c/`（见 [scripts/c/README.md](../scripts/c/README.md)）  
- 关联设备 Query/Ack 的 Python 联调脚本：`scripts/mqtt_associated_device_demo.py`  
- 简要说明：`docs/mqtt-associated-device-debug.md`

若后续协议变更，请以 **`DeviceConstant` + `MqttMessageModel` + `MqttConfig`** 三处源码为准，并同步更新本文档。
