# 平台能力清单（Capability Catalog）

| 项 | 内容 |
| --- | --- |
| **文档版本** | v1.0 |
| **日期** | 2026-07-09 |
| **状态** | 提议 / 待评审 |
| **产出依据** | [V2 架构升级设计](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md) 第七章 7.2（平台能力总线的四件事之一）、Phase 0 交付物 |
| **对应能力提供方详细设计** | [设备基座详细设计](./2026-07-08-device-foundation-detailed-design.md)、[设备通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) |

---

## 一、这份清单解决什么问题

业务架构 v1.2 把"能力解耦可组合"作为平台能力总线的核心目标：不同项目/客户应该能按需只接入部分共享底座能力（比如 SmartArm 这类第三方项目只需要设备管理 + 设备通信，不需要 GLN/数据处理/任务规划）。但"按需组合"要成立，前提是有一份**权威、可查、按能力标注依赖强弱的清单**，否则每次拆包都要重新翻两份详细设计文档、逐条判断"这个接口是不是必须的"。

这份文档就是那份清单。它面向两类读者：

- **业务应用开发者**：查"我要做的这件事，该调用底座的哪个接口/订阅哪个事件"。
- **产品/售前/交付**：查"这个项目要卖哪些能力，底座那边最少要开通什么、可以裁掉什么"。

**不覆盖的范围**：业务应用层（任务规划/GLN/数据处理/OTA/状态监控）彼此之间的私有接口——那些属于各业务模块自己的契约，不在共享底座清单里。

---

## 二、能力分类口径

### 2.1 依赖等级

| 等级 | 含义 |
| --- | --- |
| **强依赖** | 只要接入了该能力所属的底座服务，就必须调用/订阅，是业务闭环缺一不可的环节（例如：设备注册必须写 SSOT，否则设备"查不到"）|
| **可选** | 不是所有项目都需要，按打包场景决定是否集成；裁掉不影响该底座服务自身的闭环 |
| **治理能力** | 不是业务功能本身，而是让"可选打包"成立的机制（鉴权、限流、审计、契约版本），通常也是强依赖，但类型上单独归类，见第八章 |

### 2.2 打包场景（用来判定"可选"该不该选）

| 场景编号 | 名称 | 说明 |
| --- | --- | --- |
| **S1** | 全量 SaaS | 六大业务模块（任务规划/GLN/数据处理/OTA/设备管理/状态监控）全部启用，典型的睿尔曼自营 SaaS 部署 |
| **S2** | 纯设备管理 + 通信输出 | 第三方项目（如 SmartArm 业务后台、大洋电机）只需要设备基础信息查询、设备注册/凭证管理、经 HTTP-MQTT 桥接与自有设备交互，不需要 GLN/数据处理/任务规划/OTA |
| **S3** | 私有化数据处理 | 数据处理模块随包部署在客户本地（不回睿尔曼云），但仍依赖设备通信中台/设备基座提供的接口做设备核对与数据回流 |
| **S4** | 独立 OTA SKU | 只对外销售 OTA 升级能力，不含 GLN/数据处理/任务规划 |

四个场景不互斥，一个实际项目可以是多个场景的组合（例如"S1 全量 + 未来叠加 S2 对接 SmartArm"）。清单里每条能力标注它服务于哪些场景，场景列表本身也会随新项目出现而增补，不是封闭集合。

---

## 三、设备信息基础服务（`realman-device-info`）能力清单

只读为主，全部为**内部 Feign 调用**（不经对外网关）。详细字段定义见 [设备基座详细设计](./2026-07-08-device-foundation-detailed-design.md) 2.2。

| 能力 | 契约 | 消费方 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 单设备查询 | `GET /internal/device-info/{deviceId}` | GLN / 数据处理 / OTA / 状态监控 / 任务规划 | **强依赖** | S1 S2 S3 S4 | 全平台读取设备基础信息的最基本入口 |
| 按设备码查询 | `GET /internal/device-info/by-code/{deviceCode}` | OTA（`by_code` 升级）| 可选 | S1 S4 | 仅需要按设备码定位单设备的场景才用 |
| 批量查询 | `POST /internal/device-info/batch-query` | OTA / 任务规划 | 可选 | S1 S4 | 批量升级选型、版本矩阵等批量场景专用 |
| 分页/条件查询 | `GET /internal/device-info/list` | 设备管理业务平台（台账）| **强依赖** | S1 S2 S3 S4 | 只要有设备管理业务平台就需要台账数据源 |
| 注册写入 | `POST /internal/device-info/register` | 设备管理业务平台 | **强依赖** | S1 S2 S3 S4 | 设备注册闭环的必要一步，没有它 SSOT 里查不到新设备 |
| 在线事件同步 | `POST /internal/device-info/online-event` | 设备通信中台 | **强依赖** | S1 S2 S3 S4 | 只要设备接入了通信中台就会产生上下线事件 |
| 四态占用同步 | `POST /internal/device-info/occupancy-event` | 设备通信中台 | 可选 | S1 | 空闲/休眠/占用/离线四态跟踪，仅当业务需要区分"遥操/本地/自主"占用细分时才需要（S2/S4 这类第三方项目通常不需要四态语义）|
| 心跳快照同步 | `POST /internal/device-info/heartbeat-snapshot` | 设备通信中台 | 可选 | S1 S4 | OTA 前置资源校验依赖此接口写入的资源快照；不做 OTA 的项目（如纯 S2）可以不落地此接口 |
| 固件版本回写 | `PUT /internal/device-info/{deviceId}/firmware-version` | OTA 平台 | 可选 | S1 S4 | 只有开通 OTA 能力的场景才需要 |
| 测试标记同步 | `PUT /internal/device-info/{deviceId}/test-flag` | 设备管理业务平台 | 可选 | S1 S4 | 服务 OTA 高风险升级前置校验；无 OTA 场景可不接入 |
| 绑定关系同步 | `PUT /internal/device-info/{deviceId}/binding` | 设备管理业务平台 | 可选 | S1 | 主控端 ↔ 机器人绑定，仅遥操类场景（有 GLN）需要 |
| 生命周期变更 | `PUT /internal/device-info/{deviceId}/lifecycle` | 设备管理业务平台 | **强依赖** | S1 S2 S3 S4 | 设备全生命周期（激活/运行/维修/退役）管理是设备管理业务平台的基本职责，不因项目大小而可选 |

---

## 四、设备管理业务平台（`realman-device-mgmt`）能力清单

对外 REST，物理上经设备通信中台 WEB 端向网关统一路由（见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 4.2）。详细字段定义见[设备基座详细设计](./2026-07-08-device-foundation-detailed-design.md) 3.4。

### 4.1 注册与凭证

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| 设备注册 | `POST /api/v1/devices/register` | **强依赖** | S1 S2 S3 S4 | 设备接入的第一步，任何打包场景都需要 |
| 生成注册凭证 | `POST /api/v1/admin/devices/registration-secret` | **强依赖** | S1 S2 S3 S4 | 注册流程的前置依赖 |
| 查询凭证状态 | `GET /api/v1/admin/devices/{deviceSn}/registration-secret/status` | 可选 | S1 S2 | 运维排障用，非闭环必需，但建议标配 |
| 批量离线注册 | `POST /api/v1/admin/devices/offline-register/batch` | 可选 | S1 | 训练场批量场景专用，第三方小规模接入（S2/S4）通常用不到 |

### 4.2 Token / 密钥生命周期

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| Token 签发 | `POST /api/v1/devices/token/issue` | **强依赖** | S1 S2 S3 S4 | 注册流程内部触发，非独立入口，但闭环必需 |
| Token 续签 | `POST /api/v1/devices/token/refresh` | **强依赖** | S1 S2 S3 S4 | 设备/第三方长期在线运行都需要，缺了会导致 Token 过期后设备被拒 |
| Token 吊销 | `PUT /api/v1/devices/{deviceId}/token/revoke` | **强依赖** | S1 S2 S3 S4 | 安全基线能力（设备遗失/密钥泄露场景），不可裁剪 |
| 密钥重置 | `POST /api/v1/devices/{deviceId}/secret/reset` | **强依赖** | S1 S2 S3 S4 | 同上，MQTT 连接密码的安全基线能力 |

### 4.3 生命周期、四态与授权绑定

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| 生命周期变更 | `PUT /api/v1/devices/{deviceId}/lifecycle` | **强依赖** | S1 S2 S3 S4 | 见第三章对应行 |
| 创建绑定 | `POST /api/v1/devices/bindings` | 可选 | S1 | 仅遥操类场景（GLN）需要 |
| 解除绑定 | `DELETE /api/v1/devices/bindings/{bindingId}` | 可选 | S1 | 同上 |
| 查询绑定 | `GET /api/v1/devices/bindings` | 可选 | S1 | 任务规划下发前核对，同上依赖 GLN/任务规划场景 |

### 4.4 租户授权与测试标记

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| 租户授权 | `POST /api/v1/devices/{deviceId}/tenant-auth` | **强依赖** | S1 S2 S3 S4 | 只要平台是多租户 SaaS 就需要，是所有场景的基线能力 |
| 测试标记 | `PUT /api/v1/devices/{deviceId}/test-flag` | 可选 | S1 S4 | 服务 OTA 高风险管控，无 OTA 场景可不集成 |
| 批量测试标记 | `POST /api/v1/devices/test-flag/batch` | 可选 | S1 S4 | 同上 |

### 4.5 台账与审计

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| 台账列表 | `GET /api/v1/devices` | **强依赖** | S1 S2 S3 S4 | 设备管理的门面能力，任何打包场景都需要一个"看设备列表"的入口 |
| 台账详情 | `GET /api/v1/devices/{deviceId}` | **强依赖** | S1 S2 S3 S4 | 同上 |
| 操作审计查询 | `GET /api/v1/devices/audit-logs` | **强依赖** | S1 S2 S3 S4 | 安全合规基线能力，不因项目规模可选 |

---

## 五、设备通信中台 · 设备端向能力清单（MQTT，南向不对外暴露为"业务能力"，供业务应用间接使用）

以下 Topic 由通信中台归一化为 `DeviceUplinkEvent` 后转发，业务应用不直接订阅 MQTT，而是通过 Feign/内部事件消费（见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 2.2/2.4）。

| Topic | 归一化后消费方 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| `device/{code}/status/report` | 设备基座（heartbeat-snapshot）| **强依赖** | S1 S2 S3 S4 | 设备接入闭环的基本环节 |
| `$SYS/.../connected` \| `disconnected` | 设备基座（online-event）| **强依赖** | S1 S2 S3 S4 | 同上 |
| `device/{code}/ota/*`（notify/progress/heartbeat/token-refresh/status-report）| OTA 平台 | 可选 | S1 S4 | 只有开通 OTA 的场景需要 |
| `device/{code}/slam/*` | IoT 业务服务（GLN）| 可选 | S1 | 仅遥操场景需要 |
| `device/{code}/datacollect/*` | 数据处理模块（经 HTTP 直连转发，见第七章）| 可选 | S1 S3 | 仅数据处理场景（含私有化 S3）需要 |

---

## 六、设备通信中台 · WEB 端向能力清单（HTTP，对外统一网关）

详细设计见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 第四章。分两类：业务/管理 API 反向代理（真正的业务逻辑在后端服务，网关只做路由/鉴权）、HTTP-MQTT 桥接（业务逻辑在通信中台自身）。

### 6.1 业务/管理 API（反向代理，实际能力见第三、四章）

| 路径前缀 | 真实后端 | 依赖等级 | 适用场景 |
| --- | --- | --- | --- |
| `/api/v1/ota/**` | OTA 平台 | 可选 | S1 S4 |
| `/api/v1/devices`、`/api/v1/devices/{id}` | 设备基座 | **强依赖** | S1 S2 S3 S4 |
| `/api/v1/admin/devices/**` | 设备基座 | **强依赖** | S1 S2 S3 S4 |

### 6.2 HTTP-MQTT 桥接（通信中台自身承载业务逻辑）

| 能力 | 契约 | 依赖等级 | 适用场景 | 说明 |
| --- | --- | --- | --- | --- |
| 同步下行桥接 | `POST /api/v1/devices/{id}/mqtt-bridge/publish` | 可选 | S2 | 只有"第三方业务后台需要经 HTTP 直接向设备下指令"的场景（典型是 S2）才需要；S1 场景下业务应用走内部统一发布 API（`MqttPublisher`），不需要这条对外接口 |
| Webhook 订阅管理 | `POST /api/v1/webhooks/subscriptions`、`PUT /api/v1/webhooks/subscriptions/{id}/resume` | 可选 | S2 | 同上，服务第三方接收设备上行数据 |
| 轮询兜底 | `GET /api/v1/devices/{id}/events` | 可选 | S2 | 第三方无公网回调地址时的降级方案 |

**这一节是"S2 纯设备管理+通信输出"场景真正区别于 S1 的地方**：S1（全量 SaaS）里业务应用（GLN/OTA/数据处理）都是平台自己的服务，走内部 Feign/事件，不需要 6.2 这几条对外接口；只有对接**外部**第三方业务后台（S2）时，6.2 才从"不存在的能力"变成"必须开通的能力"。

---

## 七、设备通信中台 ↔ 数据处理模块直连集成能力清单

详细设计见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 第六章、[V2 主设计文档](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md) 第六章。这组能力是"同域内两个业务模块之间"的契约，不经过 WEB 端向对外网关。

| 能力 | 契约 | 方向 | 依赖等级 | 适用场景 |
| --- | --- | --- | --- | --- |
| OSS 采集授权 | `POST /internal/data-processing/oss-auth` | 通信中台 → 数据处理 | **强依赖（仅数据处理场景）** | S1 S3 |
| OSS 文件地址上报 | `POST /internal/data-processing/file-report` | 通信中台 → 数据处理 | **强依赖（仅数据处理场景）** | S1 S3 |
| 设备状态推送 | `POST /internal/data-processing/device-status` | 通信中台 → 数据处理 | 可选 | S1 S3 |
| 数采任务创建 | `POST /internal/task/data-collect-task` | 数据处理 → 通信中台/任务规划 | **强依赖（仅数据处理场景）** | S1 S3 |

不含数据处理模块的场景（纯 S2、纯 S4）完全不涉及本章能力。

---

## 八、内部事件目录（`DeviceUplinkEvent`）

事件本身不是对外 API，但决定了"谁能订阅到什么数据"，因此也纳入清单。完整 Schema 见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 5.1。

| `eventKind` | 触发来源 | 典型订阅方 | 依赖等级 |
| --- | --- | --- | --- |
| `HEARTBEAT` | `device/{code}/ota/heartbeat` | OTA 平台、状态监控 | 可选（仅 OTA/状态监控场景）|
| `OTA_PROGRESS` / `OTA_STATUS_REPORT` | `device/{code}/ota/progress` \| `/status-report` | OTA 平台 | 可选（仅 S1/S4）|
| `ONLINE` / `OFFLINE` | `$SYS/.../connected` \| `disconnected` | 设备基座、状态监控 | **强依赖** |
| `REGISTER` | `POST /internal/device/provision` | 设备管理业务平台 | **强依赖** |
| `TOKEN_REFRESH` | `device/{code}/ota/token-refresh` | 设备管理业务平台 | **强依赖** |

---

## 九、治理能力（不是业务功能，是"可选打包"成立的前提）

| 能力 | 载体 | 说明 |
| --- | --- | --- |
| 能力注册与发现 | Nacos + `*-contract` Maven 模块 | 每个共享底座服务发布一个 contract 模块作为机器可读的能力目录，本文档是其人可读镜像 |
| 统一鉴权与租户上下文透传 | Gateway/WEB 端向网关 + JWT + `X-Operator-Tenant-Id` | 详见 [V2 主设计文档](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md) 7.2 |
| 契约版本治理 | `*-contract` 模块 SemVer + 废弃公告期 | 同上 |
| API Key 授权范围控制 | WEB 端向网关（第三方系统身份）| 详见[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) 4.5，限定第三方可操作的设备范围，是 S2 场景安全基线 |

---

## 十、打包场景 × 能力组汇总矩阵

快速判断"接一个新场景，最少要开通哪些能力组"：

| 能力组 | S1 全量 SaaS | S2 设备管理+通信输出 | S3 私有化数据处理 | S4 独立 OTA SKU |
| --- | --- | --- | --- | --- |
| 设备信息基础服务（第三章）| ✔ 全部 | ✔ 只读部分 + 注册/生命周期 | ✔ 只读部分 | ✔ 只读部分 + 固件版本回写 |
| 设备管理业务平台（第四章）| ✔ 全部 | ✔ 注册/凭证/Token/租户/台账/审计（不含绑定）| ✔ 注册/凭证/Token/租户 | ✔ 注册/凭证/Token/租户/测试标记 |
| 通信中台·设备端向 MQTT（第五章）| ✔ 全部 Topic | ✔ 仅 `status/report` + 上下线 | ✔ + `datacollect/*` | ✔ + `ota/*` |
| 通信中台·WEB 端向业务/管理 API（6.1）| ✔ 全部 | ✔ 仅设备基座相关 | ✔ 仅设备基座相关 | ✔ 设备基座 + OTA |
| 通信中台·HTTP-MQTT 桥接（6.2）| ✘ 不需要 | ✔ **核心能力** | ✘ 不需要 | ✘ 通常不需要 |
| 数据处理直连集成（第七章）| ✔ | ✘ | ✔ **核心能力** | ✘ |

---

## 十一、维护说明

- **谁维护**：新增/变更共享底座接口时，由该接口的责任方（设备基座团队 / 通信中台团队）同步更新本清单，与详细设计文档同一 PR 提交。
- **何时评审**：每次迁移 Phase 收尾（见 [V2 主设计文档](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md) 第九章）前评审一次，确保清单与实际落地的接口一致。
- **新增打包场景**：出现新的第三方项目/私有化诉求时，先在第二章补充场景定义，再回填每条能力的"适用场景"列，而不是为新场景单独开一份清单。
