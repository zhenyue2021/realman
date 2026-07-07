# 设备与平台对接流程文档

**版本**: v1.0
**更新日期**: 2026-03-10
**协议**: MQTT over TCP（基于 EMQX Broker）
**服务地址**: `tcp://172.16.44.66:1883`

---

## 目录

1. [架构总览](#1-架构总览)
2. [设备鉴权认证](#2-设备鉴权认证)
3. [消息加密规范](#3-消息加密规范)
4. [Topic 主题规范](#4-topic-主题规范)
5. [设备上线流程](#5-设备上线流程)
6. [设备状态上报](#6-设备状态上报)
7. [参数配置下发与确认](#7-参数配置下发与确认)
8. [远程重启指令](#8-远程重启指令)
9. [OTA 固件升级](#9-ota-固件升级)
10. [操作日志上报](#10-操作日志上报)
11. [设备下线流程](#11-设备下线流程)
12. [摄像头视频流查询](#12-摄像头视频流查询)
13. [Redis 缓存说明](#13-redis-缓存说明)
14. [设备状态枚举](#14-设备状态枚举)
15. [数据库表结构参考](#15-数据库表结构参考)
16. [外部系统服务参数获取（STS 凭证）](#16-外部系统服务参数获取sts-凭证)
17. [WebRTC 遥操视频通话](#17-webrtc-遥操视频通话)

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          设备端 (Device)                             │
│   MQTT Client  ←─── AES-256-CBC 加密消息 ───→  订阅/发布 Topic        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ TCP:1883
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      EMQX Broker                                     │
│  ┌──────────────────┐    ┌──────────────────────────────────────┐   │
│  │  HTTP Auth/ACL   │    │  $SYS 系统事件（上下线通知）           │   │
│  │  回调 → 平台      │    │  device/+/... 业务 Topic             │   │
│  └──────────────────┘    └──────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  IOT 平台服务 (realman-iot)                           │
│                                                                      │
│  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐   │
│  │ MqttAuthCtrl    │  │ MqttMessageDisp  │  │  MqttPublisher   │   │
│  │ /internal/mqtt/ │  │ 消息分发路由      │  │  下行消息发布     │   │
│  └─────────────────┘  └──────────────────┘  └──────────────────┘   │
│                               │                                      │
│         ┌─────────────────────┼──────────────────────┐              │
│         ▼                     ▼                       ▼              │
│  OnlineOfflineHandler  DeviceStatusHandler    OtaProgressHandler    │
│  DeviceConfigAckHnd    DeviceRestartAckHnd    OperationLogHandler   │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │    MySQL     │  │    Redis     │  │       MinIO              │  │
│  │  设备/日志   │  │  缓存/在线集合│  │  OTA 固件存储             │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 设备鉴权认证（配置）

### 2.1 MQTT 连接参数

设备连接 EMQX 时须携带以下参数：

| 参数             | 值                 | 说明                              |
|----------------|-------------------|---------------------------------|
| `clientId`     | `{deviceCode}`    | 设备唯一编码/主控端防越权可使用“iot-plantform” |
| `username`     | `{deviceCode}`    | 设备唯一编码，全局唯一                     |
| `password`     | `MD5(deviceCode)` | 32位小写十六进制字符串                    |
| `cleanSession` | `false`           | 保留 QoS1 离线消息                    |

**密钥生成规则**：

```
deviceSecret = MD5(deviceCode).toLowerCase()
# 示例：
# deviceCode  = "RM-DEVICE-001"
# deviceSecret = md5("RM-DEVICE-001") → "a1b2c3d4e5f6..."（32位Hex）
```

### 2.2 EMQX 认证回调流程

EMQX 在设备发起连接时，向平台发起 HTTP 回调进行验证：

```
设备                    EMQX                      IOT平台
 │                       │                            │
 │── CONNECT ──────────→ │                            │
 │   clientId/user/pass  │── POST /internal/mqtt/auth→│
 │                       │   {                        │
 │                       │     "clientid": "RM-001",  │
 │                       │     "username": "RM-001",  │
 │                       │     "password": "a1b2c3.." │
 │                       │     "peerhost": "x.x.x.x"  │
 │                       │   }                        │
 │                       │ ←── {"result": "allow"} ───│
 │ ←── CONNACK ──────── │                            │
```

**认证接口**：

```
POST /internal/mqtt/auth
Content-Type: application/json

请求体：
{
  "clientid":  "RM-DEVICE-001",       // 设备编码
  "username":  "RM-DEVICE-001",       // 设备编码（同上）
  "password":  "a1b2c3d4e5f67890...", // MD5(deviceCode)，32位Hex
  "peerhost":  "192.168.1.100"        // 设备IP（EMQX传入）
}

响应（通过）：
{"result": "allow"}

响应（拒绝）：
{"result": "deny"}
```

**认证逻辑**：

1. 优先从 Redis 取缓存密钥（Key: `iot:device:secret:{deviceCode}`，TTL=24h）
2. 缓存未命中则查询数据库 `iot_device.device_secret`
3. 对比 `password == device_secret` 是否一致
4. 设备处于 **DISABLED（禁用）** 状态时直接拒绝

### 2.3 EMQX ACL 权限回调

设备订阅/发布 Topic 时，EMQX 向平台发起 ACL 检查：

```
POST /internal/mqtt/acl
Content-Type: application/json

请求体：
{
  "clientid": "RM-DEVICE-001",
  "username": "RM-DEVICE-001",
  "topic":    "device/RM-DEVICE-001/status/report",
  "action":   "publish"               // 或 "subscribe"
}

响应（通过）：
{"result": "allow"}
```

**ACL 规则**：设备只允许访问以 `device/{自身deviceCode}/` 开头的 Topic，禁止跨设备访问。


---

## 3. 消息加密规范

### 3.1 加密算法

所有设备与平台之间的业务消息均使用 **AES-256-CBC** 加密传输（可通过服务端配置关闭，仅用于调试）。

| 项目   | 规格                             |
|------|--------------------------------|
| 算法   | AES/CBC/PKCS5Padding           |
| 密钥长度 | 256-bit（32字节）                  |
| 密钥派生 | `SHA256(deviceCode)[0..31]`    |
| IV   | 每条消息独立随机生成 16 字节               |
| 密文编码 | Base64                         |
| 消息格式 | `{ivHex}:{base64(ciphertext)}` |

### 3.2 密钥派生

```python
import hashlib

device_code = "RM-DEVICE-001"
aes_key = hashlib.sha256(device_code.encode('utf-8')).digest()  # 32字节
```

### 3.3 加密流程（设备 → 平台）

```python
import os
import base64
import hashlib
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

def encrypt_message(device_code: str, plain_text: str) -> str:
    # 1. 派生密钥
    key = hashlib.sha256(device_code.encode('utf-8')).digest()

    # 2. 生成随机 IV（16字节）
    iv = os.urandom(16)

    # 3. AES-CBC 加密
    cipher = AES.new(key, AES.MODE_CBC, iv)
    ciphertext = cipher.encrypt(pad(plain_text.encode('utf-8'), AES.block_size))

    # 4. 拼接格式：ivHex:base64(密文)
    return iv.hex() + ":" + base64.b64encode(ciphertext).decode('utf-8')

# 示例输出：
# "a3f1e2d4b5c6789012345678901234ab:SGVsbG8gV29ybGQ="
```

### 3.4 解密流程（平台 → 设备）

```python
def decrypt_message(device_code: str, encrypted: str) -> str:
    # 1. 派生密钥
    key = hashlib.sha256(device_code.encode('utf-8')).digest()

    # 2. 分离 IV 和密文
    iv_hex, b64_cipher = encrypted.split(":", 1)
    iv = bytes.fromhex(iv_hex)
    ciphertext = base64.b64decode(b64_cipher)

    # 3. AES-CBC 解密
    cipher = AES.new(key, AES.MODE_CBC, iv)
    from Crypto.Util.Padding import unpad
    plain = unpad(cipher.decrypt(ciphertext), AES.block_size)
    return plain.decode('utf-8')
```

> **注意**：`ivHex` 固定为 32 个十六进制字符（16字节），可通过正则 `^[0-9a-f]{32}:` 判断消息是否已加密。

---

## 4. Topic 主题规范

### 4.1 上行 Topic（设备发布 → 平台订阅）

| Topic                                                       | QoS | 说明                                      | 联调情况   |
|-------------------------------------------------------------|-----|-----------------------------------------|--------|
| `device/{deviceCode}/status/report`                         | 1   | 设备周期性状态上报                               | 联调完成   | 
| `device/{deviceCode}/command/{cmd}/ack`                     | 1   | 指令集执行确认（如 `restart`、`emergency-stop` 等） | 重启联调完成 |
| `device/{deviceCode}/ota/progress`                          | 1   | OTA 升级进度上报                              | 未联调    |
| `device/{deviceCode}/log/operation`                         | 1   | 设备操作日志上报                                | 联调完成   |
| `device/{deviceCode}/camera/stream/ack`                     | 1   | 机器人上报摄像头视频流地址（见第 12 章）                  | 联调完成   |
| `master/{controllerCode}/teleop/associated-device/response` | 1   | 主控上报当前设备Mac信息                           | 已废弃    |
| `master/{controllerCode}/command/{cmd}/ack`                 | 1   | 主控设备指令 ACK（力反馈/运动与安全参数等）                | 联调完成   |
| `{robotCode}/slave/status`                                  | 1   | 机器人原始状态上报（遥操作场景，由平台透传至 WebSocket）       | 联调完成   |
| `device/{deviceCode}/ext-params/request`                    | 1   | 设备请求外部系统服务参数（如 STS 临时凭证，见第 16 章）        | 未联调    |
| `device/{robotCode}/webrtc/ack`                            | 1   | 机器人响应 WebRTC 指令 ACK（start/stop 统一 Topic，见第 17 章） | 未联调    |

### 4.2 下行 Topic（平台发布 → 设备订阅）

| Topic                                                    | QoS | 说明                               | 联调情况 |
|----------------------------------------------------------|-----|----------------------------------|------| 
| `device/{deviceCode}/command/restart`                    | 1   | 远程重启指令                           | 联调完成 |
| `device/{deviceCode}/command/emergency-stop`             | 1   | 紧急停机指令                           | 未联调  |
| `device/{deviceCode}/ota/notify`                         | 1   | OTA 升级通知                         | 未联调  |
| `device/{deviceCode}/camera/stream/query`                | 1   | 查询摄像头视频流地址（见第 12 章）              | 联调完成 |
| `master/{controllerCode}/teleop/associated-device/query` | 1   | 平台向主控查询“当前设备Mac信息”               | 联调完成 |
| `master/{controllerCode}/teleop/robot/assign`            | 1   | 平台通知主控当前应操作的机器人                  | 联调完成 |
| `master/{controllerCode}/command/force-feedback`         | 1   | 平台向主控设置力反馈参数（机械臂/夹爪力度）           | 联调完成 |
| `master/{controllerCode}/command/sport-speed`            | 1   | 平台向主控设置运动与安全参数（底盘/升降速度）          | 联调完成 |
| `device/{deviceCode}/ext-params/ack`                     | 1   | 平台响应外部系统服务参数（如 STS 临时凭证，见第 16 章） | 未联调  |
| `device/{robotCode}/webrtc/request`                             | 1   | 下发 WebRTC 统一指令（payload 中 `command` 字段区分 start/stop，见第 17 章） | 未联调  |

### 4.3 系统事件 Topic（EMQX 内部，平台订阅）

| Topic                                   | 说明     | 联调情况 |
|-----------------------------------------|--------|------|
| `$SYS/brokers/+/clients/+/connected`    | 设备上线通知 | 联调完成 |
| `$SYS/brokers/+/clients/+/disconnected` | 设备下线通知 | 联调完成 |

### 4.4 遥操作辅助 Topic（平台订阅原始上报）

> 说明：以下 Topic 主要用于主控/机器人遥操作场景，由平台统一订阅并路由给对应 Handler，设备无需感知平台内部实现细节。

| Topic                               | 说明                                        | 联调情况     |
|-------------------------------------|-------------------------------------------|----------|
| `{controllerCode}/master/cmd`       | 主控设备原始指令上报                                | 未使用-不经平台 |
| `{controllerCode}/master/states`    | 主控设备原始状态上报                                | 联调完成     |
| `{controllerCode}/master/rtsp/ctrl` | 主控设备 RTSP 控制/相关信息上报                       | 未使用-不经平台 |
| `{robotCode}/slave/cmd`             | 机器人原始指令上报                                 | 未使用-不经平台 |
| `{robotCode}/slave/status`          | 机器人原始状态上报（与 4.1 中相同，平台通过 WebSocket 转发给前端） | 联调完成     |

> **建议**：设备上线后须订阅自身的所有相关下行 Topic（配置、指令、OTA、摄像头等），使用 `cleanSession=false` 确保离线期间的下行消息不丢失。

---

## 5. 设备上线流程

```
设备                        EMQX                      IOT平台
 │                            │                           │
 │ 1. CONNECT                 │                           │
 │    clientId = deviceCode   │                           │
 │    username = deviceCode   │                           │
 │    password = MD5(code)    │                           │
 │ ─────────────────────────→ │                           │
 │                            │ 2. HTTP Auth 回调          │
 │                            │ POST /internal/mqtt/auth  │
 │                            │ ──────────────────────── →│
 │                            │ ← {"result": "allow"}     │
 │ ← CONNACK(0=成功) ──────── │                           │
 │                            │ 3. $SYS/.../connected     │
 │                            │ 推送上线事件 ─────────────→│
 │                            │              │ 4. 更新设备状态=ONLINE
 │                            │              │    记录 lastOnlineTime
 │                            │              │    加入 Redis 在线集合
 │                            │              │    写操作日志(DEVICE_ONLINE)
 │                            │              │    WebSocket 推送前端
 │                            │                           │
 │ 5. SUBSCRIBE               │                           │
 │    device/{code}/config/push         QoS=1             │
 │    device/{code}/command/restart     QoS=1             │
 │    device/{code}/ota/notify          QoS=1             │
 │ ─────────────────────────→ │                           │
 │                            │ ACL 回调 ────────────────→│
 │                            │ ← {"result": "allow"}     │
 │ ← SUBACK ───────────────── │                           │
```

---

## 6. 设备状态上报

设备在线后须**周期性**（建议 ≤5分钟）向平台上报状态，平台以此维持设备在线心跳。

### 6.1 上报 Topic

```
device/{deviceCode}/status/report
```

### 6.2 上报消息体（JSON，需 AES 加密后发送）

```json
{
  "temperature": 25.6,
  "humidity": 65.3,
  "batteryLevel": 87.5,
  "signalStrength": -72,
  "runStatus": 1,
  "longitude": 116.397428,
  "latitude": 39.909187,
  "timestamp": 1710000000000,
  "extra": {
    "customKey1": "value1",
    "customKey2": 123
  }
}
```

**字段说明**：

字段尚未完全确定

| 字段               | 类型         | 必填    | 说明                  |
|------------------|------------|-------|---------------------|
| `temperature`    | BigDecimal | 否     | 环境温度，℃              |
| `humidity`       | BigDecimal | 否     | 环境湿度，%RH            |
| `batteryLevel`   | BigDecimal | 否     | 电池电量，0~100          |
| `signalStrength` | Integer    | 否     | 信号强度，dBm，通常为负值      |
| `runStatus`      | Integer    | 否     | 设备业务运行状态，自定义枚举      |
| `longitude`      | BigDecimal | 否     | 经度，WGS84 坐标系        |
| `latitude`       | BigDecimal | 否     | 纬度，WGS84 坐标系        |
| `timestamp`      | Long       | **是** | 设备本地时间，毫秒级 Unix 时间戳 |
| `extra`          | Map        | 否     | 扩展字段，键值对任意内容        |

### 6.3 平台处理流程

```
设备上报 → 解密 → 解析 JSON
  ├─ 更新 Redis 实时缓存（TTL=6分钟）Key: iot:device:status:{deviceCode}
  ├─ 维护在线集合（Set: iot:device:online，添加 deviceCode）
  ├─ 更新 DB: status=ONLINE, lastOnlineTime, longitude, latitude
  ├─ WebSocket 推送前端实时状态
  └─ 异步写入历史表 iot_device_status（含 reportTime + receiveTime）
```

> **离线检测**：平台通过 Redis Key TTL=6分钟（设备上报间隔≤5分钟 + 1分钟缓冲）检测离线。定时任务扫描在线集合，若 Key
> 已过期则标记设备为 OFFLINE。

---

## 7. 参数配置下发与确认

### 7.1 流程概览

```
平台                                    设备
 │                                       │
 │ 1. REST API: POST /api/device/{id}/config/sync
 │    {"param1":"val1","param2":"val2"}  │
 │                                       │
 │ 2. 生成 commandId（UUID）             │
 │    保存 DB(sync_status=PENDING)       │
 │    设置等待缓存(TTL=30s)               │
 │                                       │
 │ 3. 加密 ConfigPush → MQTT发布         │
 │    Topic: device/{code}/config/push   │
 │ ─────────────────────────────────── →│
 │                                       │ 4. 解密并应用配置参数
 │                                       │
 │                          5. MQTT 发布 │
 │    Topic: device/{code}/config/ack   ←│
 │                                       │
 │ 6. 解密 ConfigAck                     │
 │    更新 DB(sync_status=SUCCESS/FAILED)│
 │    清除等待缓存                        │
 │    写操作日志                          │
```

### 7.2 下行消息：ConfigPush（平台 → 设备）

```
Topic: device/{deviceCode}/config/push
QoS: 1
```

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "params": {
    "reportInterval": 60,
    "threshold": 80.0,
    "mode": "auto"
  },
  "timestamp": 1710000000000
}
```

### 7.3 上行消息：ConfigAck（设备 → 平台）

```
Topic: device/{deviceCode}/config/ack
QoS: 1
```

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "code": 0,
  "message": "",
  "timestamp": 1710000005000
}
```

---

## 8. 远程重启指令

### 8.1 流程概览

```
平台                                    设备
 │                                       │
 │ 1. REST API: POST /api/device/{id}/restart
 │    {"reason":"设备故障"}              │
 │                                       │
 │ 2. 生成 commandId                     │
 │    记录操作日志(PENDING)               │
 │                                       │
 │ 3. 加密 RemoteRestartCommand → MQTT   │
 │    Topic: device/{code}/command/restart
 │ ─────────────────────────────────── →│
 │                                       │ 4. 收到指令，决策是否执行
 │                                       │
 │                          5. MQTT 发布 │
 │  Topic: device/{code}/command/restart/ack ←│
 │                                       │
 │ 6. 解密 RestartAck                    │
 │    更新操作日志(SUCCESS/FAIL)          │
```

### 8.2 下行消息：RemoteRestartCommand（平台 → 设备）

```
Topic: device/{deviceCode}/command/restart
QoS: 1
```

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440001",
  "reason": "设备故障，需重启恢复",
  "timestamp": 1710000000000
}
```

### 8.3 上行消息：RestartAck（设备 → 平台）

```
Topic: device/{deviceCode}/command/restart/ack
QoS: 1
```

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440001",
  "code": 0,
  "message": "",
  "timestamp": 1710000003000
}
```

> **注意**：设备收到重启指令后应先回复 ACK（`code=0`），再执行重启操作，避免重启后 ACK 消息丢失。

---

## 9. OTA 固件升级

### 9.1 完整升级流程

```
平台                                    设备
 │                                       │
 │ [管理员操作]                           │
 │ 1. 分片上传固件到 MinIO                │
 │    POST /api/ota/firmware/upload/chunk │
 │    POST /api/ota/firmware/upload/merge │
 │                                       │
 │ 2. 创建升级任务                        │
 │    POST /api/ota/task/create           │
 │                                       │
 │ 3. 执行升级任务                        │
 │    POST /api/ota/task/{taskId}/execute │
 │    向在线设备发送 OtaNotify            │
 │ ─────────────────────────────────── →│
 │                                       │ 4. 收到通知，开始下载固件
 │                                       │    通过 downloadUrl 下载
 │                                       │    验证 MD5
 │                          5. 上报进度  │
 │ Topic: device/{code}/ota/progress ←── │ (status=DOWNLOADING)
 │ ─────────────────────────────────── →│
 │                          6. 上报完成  │
 │ Topic: device/{code}/ota/progress ←── │ (status=DOWNLOADED)
 │                                       │
 │                          7. 安装固件  │
 │ Topic: device/{code}/ota/progress ←── │ (status=INSTALLING)
 │                                       │
 │                        8a. 升级成功  │
 │ Topic: device/{code}/ota/progress ←── │ (status=SUCCESS, newVersion)
 │ 平台更新设备固件版本                   │
 │                                       │
 │                        8b. 升级失败  │
 │ Topic: device/{code}/ota/progress ←── │ (status=FAILED, failReason)
```

### 9.2 下行消息：OtaNotify（平台 → 设备）

```
Topic: device/{deviceCode}/ota/notify
QoS: 1
```

```json
{
  "taskId": "task-uuid-001",
  "recordId": "record-uuid-001",
  "firmwareId": "firmware-uuid-001",
  "version": "v2.1.0",
  "downloadUrl": "http://minio:9001/iot-firmware/v2.1.0.bin?X-Amz-...",
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileSize": 2097152,
  "forceUpgrade": 1,
  "timestamp": 1710000000000
}
```

**字段说明**：

| 字段             | 类型      | 说明                    |
|----------------|---------|-----------------------|
| `taskId`       | String  | 升级任务ID                |
| `recordId`     | String  | 本设备升级记录ID（后续上报进度必须携带） |
| `firmwareId`   | String  | 固件ID                  |
| `version`      | String  | 目标固件版本号               |
| `downloadUrl`  | String  | MinIO 预签名下载URL，7天有效   |
| `fileMd5`      | String  | 固件文件 MD5 校验值          |
| `fileSize`     | Long    | 固件文件大小（字节）            |
| `forceUpgrade` | Integer | 1=强制升级，0=可选升级         |
| `timestamp`    | Long    | 通知发送时间，毫秒时间戳          |

### 9.3 上行消息：OtaProgress（设备 → 平台）

```
Topic: device/{deviceCode}/ota/progress
QoS: 1
```

```json
{
  "taskId": "task-uuid-001",
  "recordId": "record-uuid-001",
  "status": 3,
  "progress": 45,
  "downloadedBytes": 943718,
  "failReason": "",
  "newVersion": "v2.1.0",
  "timestamp": 1710000060000
}
```

**字段说明**：

| 字段                | 类型      | 说明                            |
|-------------------|---------|-------------------------------|
| `taskId`          | String  | 升级任务ID                        |
| `recordId`        | String  | 升级记录ID（对应 OtaNotify.recordId） |
| `status`          | Integer | 升级状态码（见下方枚举）                  |
| `progress`        | Integer | 下载进度百分比，0~100                 |
| `downloadedBytes` | Long    | 已下载字节数（用于断点续传）                |
| `failReason`      | String  | 失败原因（status=FAILED 时填写）       |
| `newVersion`      | String  | 升级成功后的新版本（status=SUCCESS 时填写） |
| `timestamp`       | Long    | 上报时间，毫秒时间戳                    |

**升级状态枚举**：

| code | 名称          | 说明                 |
|------|-------------|--------------------|
| 0    | PENDING     | 待通知（平台侧）           |
| 1    | NOTIFIED    | 已发送通知              |
| 2    | CONFIRMED   | 设备已确认收到            |
| 3    | DOWNLOADING | 下载中（持续上报 progress） |
| 4    | DOWNLOADED  | 下载完成，等待安装          |
| 5    | INSTALLING  | 安装中                |
| 6    | SUCCESS     | 升级成功               |
| 7    | FAILED      | 升级失败               |
| 8    | TIMEOUT     | 超时（平台检测）           |

> **断点续传**：平台将 `downloadedBytes` 缓存于 Redis（TTL=40分钟），设备重连后可查询断点偏移量继续下载。

---

## 10. 操作日志上报

设备可主动上报本地发生的操作事件。

### 10.1 上行消息：OperationLogReport

```
Topic: device/{deviceCode}/log/operation
QoS: 1
```

```json
{
  "operationType": "PARAM_MODIFY",
  "operationDesc": "本地修改上报间隔参数",
  "operationDetail": "{\"reportInterval\":30}",
  "operationResult": "SUCCESS",
  "operationTime": 1710000000000
}
```

**字段说明**：

| 字段                | 类型     | 说明                      |
|-------------------|--------|-------------------------|
| `operationType`   | String | 操作类型（见枚举表）              |
| `operationDesc`   | String | 操作描述                    |
| `operationDetail` | String | 操作详情（建议 JSON 字符串格式）     |
| `operationResult` | String | 执行结果：`SUCCESS` / `FAIL` |
| `operationTime`   | Long   | 操作发生时间，毫秒时间戳            |

**操作类型枚举**：

| operationType      | 说明   |
|--------------------|------|
| `PARAM_MODIFY`     | 参数修改 |
| `FIRMWARE_UPGRADE` | 固件升级 |
| `REMOTE_RESTART`   | 远程重启 |
| `DEVICE_ONLINE`    | 设备上线 |
| `DEVICE_OFFLINE`   | 设备下线 |
| `DEVICE_REGISTER`  | 设备注册 |
| `COMMAND_SEND`     | 指令发送 |
| `SECRET_RESET`     | 密钥重置 |

---

## 11. 设备下线流程

```
设备                        EMQX                      IOT平台
 │                            │                           │
 │── DISCONNECT / 断线 ──────→│                           │
 │                            │ $SYS/.../disconnected     │
 │                            │ 推送下线事件 ─────────────→│
 │                            │              │ 1. 更新设备状态=OFFLINE
 │                            │              │    记录 lastOfflineTime
 │                            │              │    从 Redis 在线集合移除
 │                            │              │    删除状态缓存
 │                            │              │    写操作日志(DEVICE_OFFLINE)
 │                            │              │    WebSocket 推送前端
```

下线事件 Payload 包含断线原因字段 `reason`，平台会记录到操作日志。

---

## 12. 摄像头视频流查询

Web 通过平台 REST 接口发起摄像头视频流地址查询，平台再通过 MQTT 与机器人交互，整体流程如下。

### 12.1 流程概览

```
Web                         IOT平台                            机器人
 │                            │                                   │
 │ GET /api/device/{id}/camera/stream 或 /camera/stream/{index}   │
 │───────────────────────────→│                                   │
 │                            │ 1. 校验设备存在且在线             │
 │                            │ 2. 生成 commandId                 │
 │                            │ 3. 注册 Future(挂起等待)          │
 │                            │ 4. 下发 CameraStreamQuery         │
 │                            │    Topic: device/{code}/camera/stream/query
 │                            │──────────────────────────────────→│
 │                            │                                   │ 5. 解密并解析 Query
 │                            │                                   │ 6. 查询指定/全部摄像头流地址
 │                            │                                   │ 7. 上报 CameraStreamResponse
 │                            │             device/{code}/camera/stream/ack ←────│
 │ 8. 处理响应，完成 Future     │                                   │
 │ 9. 将流地址列表返回给 Web    │──────────────────────────────────→│
```

> 说明：`cameraIndex = null` 表示查询全部摄像头；非 null 且 ≥0 表示仅查询指定路数。  
> Web 端也可以只拿第一条结果作为“当前摄像头”的流地址。

### 12.2 下行消息：CameraStreamQuery（平台 → 机器人）

```
Topic: device/{deviceCode}/camera/stream/query
QoS: 1
Payload: AES-256-CBC 加密后的 JSON 字符串
```

未加密前的 JSON 结构为：

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440010",
  "cameraIndex": null,
  "timestamp": 1710000000000
}
```

- **commandId**：本次查询的唯一标识（UUID），机器人在响应时必须原样带回。
- **cameraIndex**：
    - `null`：查询全部摄像头；
    - `0, 1, 2, ...`：查询指定路摄像头的流地址。
- **timestamp**：平台发送时间，毫秒时间戳。

### 12.3 上行消息：CameraStreamResponse（机器人 → 平台）

```
Topic: device/{deviceCode}/camera/stream/ack
QoS: 1
Payload: AES-256-CBC 加密后的 JSON 字符串
```

未加密前的 JSON 结构示例：

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440010",
  "code": 0,
  "message": "",
  "cameras": [
    {
      "cameraIndex": 0,
      "cameraName": "front",
      "streamUrl": "rtsp://192.168.1.10/live/front",
      "streamType": "rtsp"
    },
    {
      "cameraIndex": 1,
      "cameraName": "rear",
      "streamUrl": "rtsp://192.168.1.10/live/rear",
      "streamType": "rtsp"
    }
  ],
  "timestamp": 1710000005000
}
```

字段说明：

| 字段          | 类型              | 说明                                 |
|-------------|-----------------|------------------------------------|
| `commandId` | String          | 对应下行 `CameraStreamQuery.commandId` |
| `code`      | Integer         | 执行结果：0=成功，非 0=失败（如不支持、内部错误等）       |
| `message`   | String          | 失败原因（`code != 0` 时填写），成功可为空字符串     |
| `cameras`   | Array\<Object\> | 摄像头列表，元素结构见下表                      |
| `timestamp` | Long            | 设备回复时间，毫秒时间戳                       |

`cameras` 中单个元素（对应 CameraInfo）结构：

| 字段            | 类型      | 说明                                                 |
|---------------|---------|----------------------------------------------------|
| `cameraIndex` | Integer | 摄像头路数索引，从 0 开始，与 Query 中的索引对应                      |
| `cameraName`  | String  | 摄像头名称/标识，可为空                                       |
| `streamUrl`   | String  | 视频流地址（如 `rtsp://`、`rtmp://`、`http(s)://...m3u8` 等） |
| `streamType`  | String  | 流类型（如 `rtsp`、`rtmp`、`hls`），可为空                     |

> 注意：当 `cameraIndex` 为某个非空索引时，平台预期 `cameras` 只返回该索引对应的一条记录；  
> 当 `cameraIndex = null` 时，建议返回所有可用摄像头的列表。

### 12.4 设备端实现要点

- 订阅下行 Topic：`device/{deviceCode}/camera/stream/query`。
- 解密收到的消息，解析出：
    - `commandId`：必须原样带回给平台；
    - `cameraIndex`：`null`=全部，非 null=指定路；
    - `timestamp`：可用于本地超时控制或日志。
- 根据 `cameraIndex`：
    - 若为 `null`，枚举设备上所有摄像头，构建 `cameras` 数组；
    - 若为非负整数，仅返回对应路数（若不存在该路，可返回 `code!=0` 并在 `message` 中说明）。
- 构造 `CameraStreamResponse`，加密后发布到：
    - `device/{deviceCode}/camera/stream/ack`，QoS=1。

---

## 13. Redis 缓存说明

| Key 格式                                     | 类型     | TTL     | 说明                              |
|--------------------------------------------|--------|---------|---------------------------------|
| `iot:device:status:{deviceCode}`           | String | **6分钟** | 设备最新状态 JSON，TTL 过期即视为离线         |
| `iot:device:secret:{deviceCode}`           | String | 24小时    | 设备密钥缓存，避免每次鉴权查库                 |
| `iot:device:aeskey:{deviceCode}`           | String | 24小时    | AES Key 缓存（SHA256派生结果）          |
| `iot:device:online`                        | Set    | 永久      | 在线设备编码集合，结合 status Key TTL 判断离线 |
| `iot:config:sync:{deviceCode}:{commandId}` | String | 30秒     | 配置下发等待 ACK 标记                   |
| `iot:ota:progress:{deviceCode}:{recordId}` | String | 40分钟    | OTA 断点续传进度（已下载字节数）              |
| `iot:upload:chunk:{uploadId}`              | Hash   | —       | 固件分片上传进度                        |
| `iot:room:master:{masterCode}`             | String | 24小时    | 主控房间信息 JSON（DeviceRoomVO）        |
| `iot:room:robot:{robotCode}`               | String | 24小时    | 机器人 → 主控编码反查索引                  |
| `iot:room:turn-route:{masterCode}`         | String | 24小时    | 房间 TURN/信令路由缓存（含 serverIp、signalKey） |
| `iot:room:active`                          | Set    | 永久      | 活跃房间主控编码集合                      |

---

## 14. 设备状态枚举

### 设备在线状态

| code | 名称       | 说明              |
|------|----------|-----------------|
| 0    | INACTIVE | 未激活（新建设备，从未连接过） |
| 1    | ONLINE   | 在线              |
| 2    | OFFLINE  | 离线              |
| 3    | DISABLED | 禁用（拒绝认证）        |

---

## 16. 外部系统服务参数获取（STS 凭证）

设备需要访问外部对象存储时，须先向平台请求 STS 临时凭证。所有设备共享同一套参数，平台从 Redis 缓存（TTL
跟随凭证过期时间）读取后下发；缓存未命中时自动降级查库。

### 16.1 交互流程

```
设备                                         IOT 平台
  │                                               │
  │── ext-params/request ────────────────────────>│  1. 设备请求 STS 参数
  │   {commandId, sourceSystem}                   │     解密 → 读 Redis（key: realman:ext:param:{sourceSystem}）
  │                                               │     缓存未命中 → 降级查库 → 回写缓存
  │<── ext-params/ack ──────────────────────────  │  2. 平台响应凭证（加密下发）
  │   {commandId, code, endpoint, bucket, ...}    │
```

### 16.2 上行消息：ExtParamsRequest（设备 → 平台）

```
Topic: device/{deviceCode}/ext-params/request
QoS: 1
```

```json
{
  "requestId": "req_084ecb37bbd8",
  "sourceSystem": "RM_FUNC_MCAP_UPLOADER",
  "targetSystem": "GLN_MANAGE_PLATFORM",
  "bizType": "upload_url_request",
  "timestamp": "2026-02-25T16:28:32.6247124",
  "params": {
    "dataType": "MCAP",
    "deviceId": "realbot_001",
    "fileSize": 104857600
  }
}
```



| 字段             | 类型     | 必填 | 说明                           |
|----------------|--------|----|------------------------------|
| `requestId`    | String | 是  | 请求唯一标识，响应时原样返回，用于对账          |
| `sourceSystem` | String | 是  | 固定值：`RM_FUNC_MCAP_UPLOADER` |
| `targetSystem` | String | 是  | 固定值：`GLN_MANAGE_PLATFORM` |
| `bizType` | String | 是  | 固定值：`upload_url_request` |
| `timestamp` | String | 是  | UTC+8 时间戳，精确到毫秒 |
| `params.dataType` | String | 是  | 固定值：`MCAP` |
| `params.deviceId` | String | 是  | 设备唯一标识 |
| `params.fileSize` | int    | 否  | 待上传文件大小（字节） |

### 16.3 下行消息：ExtParamsResponse（平台 → 设备）

```
Topic: device/{deviceCode}/ext-params/ack
QoS: 1
```

**成功（code=0）**

```json
{
  "requestId": "req_084ecb37bbd8",
  "sourceSystem": "GLN_MANAGE_PLATFORM",
  "targetSystem": "RM_FUNC_MCAP_UPLOADER",
  "bizType": "upload_url_response",
  "timestamp": "2026-02-25T16:28:33.1247124",
  "code": 200,
  "message": "success",
  "params": {
    "timestamp": "2026-02-25T16:28:32.6247124",
    "data": {
      "endpoint": "sts.cn-beijing.aliyuncs.com",
      "bucket": "embodied-data",
      "bjExpiration": "2026-02-25 17:28:15",
      "utcExpiration": "2026-02-25T09:28:15Z",
      "accessKeyId": "STS.NYx3uEBnMWqC3ogAa14JAFM6y",
      "accessKeySecret": "Ai36sQjvJgoXoyusBkNJCYjAKup9Vy7g7JW2EsQj7v1h",
      "securityToken": "CAISxwJ1q6Ft5B2yfSjIr5rNeM..."
    }
  }
}
```

**失败（code=400，缓存与库中均无数据）**

```json
{
  "requestId": "req_084ecb37bbd8",
  "sourceSystem": "GLN_MANAGE_PLATFORM",
  "targetSystem": "RM_FUNC_MCAP_UPLOADER",
  "bizType": "upload_url_response",
  "timestamp": "2026-02-25T16:28:33.1247124",
  "code": 400,
  "message": "暂无可用的外部服务参数，请稍后重试"
}
```

| 字段                       | 类型     | 说明                                             |
|--------------------------|--------|------------------------------------------------|
| `requestId`              | String | 与请求一致                                          |
| `code`                   | int    | `0`=成功，`400`=暂无数据                              |
| `message`                | String | 失败描述，`code=0` 时为 `null`                        |
| `sourceSystem`           | String | 是  | 固定值：`RM_FUNC_MCAP_UPLOADER` |
| `targetSystem`           | String | 是  | 固定值：`GLN_MANAGE_PLATFORM` |
| `bizType`                | String | 是  | 固定值：`upload_url_request` |
| `timestamp`              | String | 是  | UTC+8 时间戳，精确到毫秒 |
| `params.timestamp`       | String | 与达尔文推送一致                                |
| `params.data.endpoint`   | String | STS endpoint 地址                                |
| `params.data.bucket`          | String | OSS Bucket 名称                                  |
| `params.data.bjExpiration`    | String | 凭证北京时间过期时间（`yyyy-MM-dd HH:mm:ss`）              |
| `params.data.utcExpiration`   | String | 凭证 UTC 过期时间（ISO-8601，如 `2026-02-25T09:28:15Z`） |
| `params.data.accessKeyId`     | String | STS AccessKeyId                                |
| `params.data.accessKeySecret` | String | STS AccessKeySecret                            |
| `params.data.securityToken`   | String | STS SecurityToken（长字符串）                        |

### 16.4 设备端行为建议

1. **按需请求**：在需要访问 OSS 前发起请求，不必每次操作都重新请求。
2. **凭证刷新**：比对 `bjExpiration`，在过期前（建议提前 5 分钟）主动重新请求。
3. **重试策略**：收到 `code=400` 时，等待一段时间后重试（外部系统尚未推送参数至平台）。
4. **commandId**：每次请求使用唯一值（如 UUID 或时间戳+随机数），便于与响应对账。

---

## 17. WebRTC 遥操视频通话

遥操开始时，平台通过 `device/{robotCode}/webrtc/request`（payload 中 `command="start"`）向**机器人**下发 WebRTC 房间信息，机器人连接信令服务器并建立 P2P 视频通道；遥操停止时，平台通过同一 Topic 下发 `command="stop"`（fire-and-forget）拆除连接。

> **注意**：`device/.../webrtc/...` Topic 中的 `{deviceCode}` 均为**机器人**设备编码（`robotCode`），非主控编码。Payload 同样使用 `AES-256-CBC` 加密（密钥由 `robotCode` 派生）。

### 17.1 流程概览

```
平台                                        机器人
  │                                            │
  │── device/{robotCode}/webrtc/request ─────────────>│  1. 平台下发 WebRTC 开始指令（command="start"，含房间信息）
  │<── device/{robotCode}/webrtc/ack ─────────│  2. 机器人连接信令服务器成功后回复（5 秒超时）
  │                                            │
  │   （WebRTC P2P 建立，主控与机器人视频互通）  │
  │                                            │
  │── device/{robotCode}/webrtc/request ─────────────>│  3. 遥操结束，平台下发停止指令（command="stop"，不等待 ACK）
  │<── device/{robotCode}/webrtc/ack ─────────│  4. 机器人可选回复（平台仅记日志）
```

> **超时**：平台等待 `command/ack`（start）最多 **5 秒**，超时或 `success=false` 均中止遥操并向调用方返回错误。

### 17.2 下行消息：WebRtcCommand — start（平台 → 机器人）

```
Topic: device/{robotCode}/webrtc/request
QoS: 1
Payload: AES-256-CBC 加密（密钥由 robotCode 派生）
```

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

**字段说明**：

| 字段            | 类型            | 说明                                                         |
|---------------|---------------|------------------------------------------------------------|
| `command`     | String        | 固定为 `"start"`                                             |
| `commandId`   | String        | 唯一指令 ID（UUID），ACK 中必须原样带回                               |
| `roomId`      | String        | 平台为本次遥操分配的房间号（雪花 ID 字符串）                                |
| `signalUrl`   | String        | 信令服务器 IP 地址，机器人据此建立 WebSocket 连接                        |
| `signalKey`   | String        | 信令服务器访问密钥，由 turn_router 调度接口返回，随房间路由缓存（TTL=24h）              |
| `turnServers` | Array<Object> | TURN 中继服务器列表，`url` 格式为 `turn:host:port?transport=udp`    |
| `stunServers` | Array<String> | STUN 服务器地址列表，格式为 `stun:host:port`                        |
| `timestamp`   | Long          | 平台下发时间，毫秒时间戳                                             |

### 17.3 上行消息：WebRtcAck（机器人 → 平台）

```
Topic: device/{robotCode}/webrtc/ack
QoS: 1
Payload: AES-256-CBC 加密（密钥由 robotCode 派生）
```

**成功**：

```json
{
  "command": "start",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "success": true,
  "message": null,
  "timestamp": 1710000000500
}
```

**失败**：

```json
{
  "command": "start",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "success": false,
  "message": "信令服务器连接超时",
  "timestamp": 1710000000500
}
```

| 字段          | 类型      | 说明                                               |
|-------------|---------|--------------------------------------------------|
| `command`   | String  | 回复的指令类型：`start` 或 `stop`                        |
| `commandId` | String  | 与下行指令一致                                         |
| `success`   | Boolean | `true`=WebRTC 已成功建立；`false`=失败，平台将中断遥操          |
| `message`   | String  | 失败原因（`success=true` 时为 `null`）                   |
| `timestamp` | Long    | 机器人回复时间，毫秒时间戳                                   |

### 17.4 下行消息：WebRtcCommand — stop（平台 → 机器人）

```
Topic: device/{robotCode}/webrtc/request
QoS: 1
Payload: AES-256-CBC 加密（密钥由 robotCode 派生）
```

```json
{
  "command": "stop",
  "commandId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "timestamp": 1710000060000
}
```

### 17.5 上行消息：WebRtcAck（机器人 → 平台，可选）

```
Topic: device/{robotCode}/webrtc/ack
QoS: 1
```

平台收到后仅记录日志，不影响任何流程。Payload 结构与 start ACK 一致（`command="stop"`）。

### 17.6 设备端实现要点

1. 订阅 `device/{robotCode}/webrtc/request`（单一 Topic，无通配符）。
2. 收到消息后解密，读取 `command` 字段判断指令类型。
3. `command="start"` 时：根据 `signalUrl` + `signalKey` 连接信令服务器，在 **5 秒内** 完成并回复 ACK（`command="start"`, `success=true`）。
4. 若连接失败，立即回复 ACK（`command="start"`, `success=false`，`message` 填写失败原因）。
5. `commandId` 必须在 ACK 中原样带回，平台据此关联等待的 Future。
6. `command="stop"` 时：主动断开 WebRTC 连接，可选回复 ACK（`command="stop"`，平台仅记日志）。

---

## 附录：对接快速检查清单

**设备端必须实现**：

- [ ] MQTT 连接时 `password = MD5(deviceCode)`（32位小写Hex）
- [ ] 连接后订阅下行 Topic（`config/push`、`command/restart`、`ota/notify`、`camera/stream/query`、`ext-params/ack`）
- [ ] 所有业务消息使用 AES-256-CBC 加密/解密，密钥由 `SHA256(deviceCode)` 派生
- [ ] 每次上报状态消息中包含 `timestamp` 字段（毫秒时间戳）
- [ ] 状态上报间隔 **≤ 5分钟**（否则平台将判定为离线）
- [ ] 收到 ConfigPush 后应用配置并回复 ConfigAck（携带相同 `commandId`）
- [ ] 收到 RemoteRestartCommand 后**先回复 RestartAck** 再执行重启
- [ ] OTA 升级过程中定期上报 progress，终态（SUCCESS/FAILED）必须上报
- [ ] 设备支持摄像头：订阅 `device/{deviceCode}/camera/stream/query`，收到 CameraStreamQuery 后按 `commandId`、`cameraIndex`
  查询流地址，加密后上报到 `device/{deviceCode}/camera/stream/ack`（响应中必须带回相同 `commandId`）
- [ ] `cleanSession = false`，确保离线期间下行消息不丢失
- [ ] 机器人订阅 `device/{robotCode}/webrtc/request`，收到后读取 payload 中 `command` 字段：`start` 时连接信令服务器，在 **5 秒内** 回复 ACK（`commandId` 必须一致）；`stop` 时断开 WebRTC 连接

