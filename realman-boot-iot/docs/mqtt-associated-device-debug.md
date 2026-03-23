# 主控「关联设备」MQTT 上下行联调说明

对应常量：`DeviceConstant.MqttTopic.ASSOCIATED_DEVICE_QUERY` / `ASSOCIATED_DEVICE_ACK`  
消息模型：`MqttMessageModel.AssociatedDeviceQuery`、`AssociatedDeviceResponse`

## Topic 与方向

| 方向 | Topic 模板 | 说明 |
|------|------------|------|
| 下行（平台 → 主控） | `master/{controllerCode}/teleop/associated-device/query` | 平台查询当前关联机器人等信息 |
| 上行（主控 → 平台） | `master/{controllerCode}/teleop/associated-device/ack` | 主控应答，须带与 Query 相同的 `commandId` |

> 注释里若仍写 `device/{code}/...`，以常量中的 `master/%s/...` 与 `MqttConfig` 订阅为准。

## Payload（明文 JSON）

**下行 Query 示例：**

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "operatorId": "op001",
  "loginLogId": null,
  "timestamp": 1710000000000
}
```

**上行 Ack 示例（成功）：**

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "code": 0,
  "message": null,
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "timestamp": 1710000000100
}
```

生产环境默认对 Payload 做 **Per-Device AES-256-CBC**，格式：`{32位hex IV}:{Base64密文}`，密钥为 `SHA256(deviceCode)` 的前 32 字节（与 `CommandEncryptService` 一致）。  
本地可将 `device.encrypt.enabled=false`，此时可为 **明文 JSON**，便于用 Mosquitto 客户端直接测。

## Python 脚本 Demo

路径：`scripts/mqtt_associated_device_demo.py`

```bash
pip install paho-mqtt pycryptodome
```

**终端 1 — 模拟主控（订阅 Query，自动加密回 Ack）：**

```bash
python scripts/mqtt_associated_device_demo.py --host 127.0.0.1 --port 1883 -c YOUR_CTRL_CODE fake-controller
```

**终端 2 — 模拟平台下发 Query：**

```bash
python scripts/mqtt_associated_device_demo.py --host 127.0.0.1 -c YOUR_CTRL_CODE publish-query
```

**仅手动发 Ack（`commandId` 需与 Query 一致）：**

```bash
python scripts/mqtt_associated_device_demo.py --host 127.0.0.1 -c YOUR_CTRL_CODE publish-ack --command-id <同上commandId>
```

**明文模式（需平台 `device.encrypt.enabled=false`）：** 上述命令加 `--plain`。

**监听 Ack：**

```bash
python scripts/mqtt_associated_device_demo.py --host 127.0.0.1 -c YOUR_CTRL_CODE sniff-ack
```

## 与 Java 业务的关系

- 平台下发：`MasterLoginResolveServiceImpl` 通过 `MqttPublisher.publishToDevice(controllerCode, topic, json, 1)`，内部会加密。
- 平台接收：`MasterAssociatedDeviceResponseHandler` 解密后按 `commandId` 完成 `MasterAssociatedDevicePendingService` 中的 Future。

## Broker 与账号

默认与 `application` 中 `mqtt.broker.*` 一致；若 EMQX 开启 HTTP 鉴权，脚本连接需使用允许的客户端账号（测试环境可单独开匿名或专用用户）。
