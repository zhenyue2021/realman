# ADR-0001: IoT 平台拆分为设备中台、MQTT 集成平台与 OTA 平台

| 项 | 内容 |
|---|---|
| **状态** | 提议 |
| **日期** | 2026-06-30 |
| **决策者** | 架构组 / IoT 团队（待确认） |
| **影响模块** | `realman-boot-iot`、`realman-server-cloud`（Gateway）、新建 `realman-boot-device-hub`、`realman-boot-mqtt-hub`、`realman-boot-ota` |
| **详细设计** | [IoT 平台架构升级设计](../design/2026-06-30-iot-platform-architecture-upgrade.md) |

---

## 背景

当前 `realman-iot` 微服务（8085）将设备管理、MQTT 协议集成、OTA 升级、工单、SLAM、WebRTC 等能力内聚于单一进程。随着设备规模与业务场景扩展，出现以下问题：

- 无法对 MQTT 接入层、OTA 编排、设备主数据分别扩缩容与独立发版。
- 新增 MQTT Topic 或设备类型需修改同一服务，变更风险集中。
- OTA 与 MQTT Handler、WebSocket 强耦合，难以作为独立产品演进。
- 模块 `api` 依赖 `biz`，契约边界不清晰。

业务上需要：**设备管理中台**、**MQTT 集成平台**、**独立 OTA 升级平台**，IoT 服务聚焦场景业务。

---

## 决策

将现有 `realman-iot` 按职责拆分为四个微服务：

| 服务 | 应用名 | 端口 | 职责 |
|------|--------|------|------|
| 设备管理中台 | `realman-device-hub` | 8086 | 设备 SSOT：注册、密钥、租户授权、在线状态、配置 |
| MQTT 集成平台 | `realman-mqtt-hub` | 8087 | EMQX Auth/ACL、消息路由、下行发布、集群 ACK |
| OTA 升级平台 | `realman-ota` | 8088 | 固件存储、升级任务、进度状态机 |
| IoT 业务服务 | `realman-iot` | 8085 | 工单、SLAM、WebRTC、Darwin（瘦身） |

**通信约定：**

- 同步查询（设备信息、密钥校验、MQTT 下行）：**OpenFeign** + `*-contract` 模块。
- MQTT 上行至业务/OTA：**RocketMQ 事件**（优先沿用现有 RocketMQ 实践）。
- 跨 Pod MQTT ACK 等待：**Redis Pub/Sub**（保留于 MQTT 平台）。
- AES 设备报文加解密：**统一在 MQTT 平台**处理。

**迁移顺序：** OTA 平台 → MQTT 平台 → 设备中台 → IoT 瘦身（详见设计文档 Phase 0–4）。

---

## 备选方案

### 方案 A：维持单体 IoT，仅做模块内 package 拆分

- **优点**：零部署变更、迁移成本最低。
- **缺点**：无法独立扩缩容；发版仍全量；不满足「中台/平台」产品化目标。
- **结论**：不采纳。

### 方案 B：仅拆 OTA，设备与 MQTT 保留在 IoT

- **优点**：Phase 1 改动面小。
- **缺点**：MQTT 与设备仍耦合；Auth 与 CRUD 同进程问题未解。
- **结论**：作为过渡可接受，但不作为终态；终态仍为三平台 + 瘦身 IoT。

### 方案 C：MQTT 与设备合并为「设备接入中台」

- **优点**：减少一次 Feign（Auth 校验）。
- **缺点**：MQTT 连接管理与设备 CRUD 生命周期不同，合并后仍难独立扩容 MQTT 层。
- **结论**：不采纳；Auth 校验通过 Feign + 短 TTL 缓存缓解延迟。

---

## 后果

### 正面

- 各平台可独立部署、扩缩容与版本管理。
- Topic 路由可插件化注册，新业务接入 IoT 无需改 MQTT 核心。
- OTA 可对接多产品线固件与升级策略。
- 设备主数据成为 SSOT，便于对外 Open API。

### 负面 / 代价

- 微服务数量由 2 增至 5（含 System），运维与监控复杂度上升。
- 平台间调用引入 Feign/MQ 延迟与故障传播，需熔断、缓存与 Saga。
- 拆分期间需兼容代理与 Feature Flag，迁移周期约 **14–18 周**（按 Phase 估算）。
- EMQX Auth 回调 URL 切换需窗口期与回滚预案。

### 需跟进事项

- [ ] 评审并批准本 ADR
- [ ] 创建 `*-contract` 模块与 Nacos 配置模板
- [ ] Gateway 路由与 EMQX 直连策略落地
- [ ] 更新 [微服务架构图](../realman-boot-microservices-architecture.md) 与部署 Runbook
- [ ] Phase 1 OTA 拆分排期

---

## 参考

- [IoT 平台架构升级设计（完整方案）](../design/2026-06-30-iot-platform-architecture-upgrade.md)
- [IoT 模块分层说明](../../realman-boot-iot/docs/IOT-MODULE-LAYERING.md)
- [设备在线状态设计](../../realman-boot-iot/docs/IOT-DEVICE-ONLINE-STATE.md)
- [Realman-Boot 微服务架构](../realman-boot-microservices-architecture.md)
