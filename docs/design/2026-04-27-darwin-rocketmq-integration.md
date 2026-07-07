# 达尔文数采平台 RocketMQ 集成设计

> **已被取代**：SaaS 平台化后数据处理模块（Darwin）与设备通信中台同域，不再需要跨系统 RocketMQ 桥接，本设计的 4 条链路已改为 HTTP 直连方案，详见 [V2 架构升级设计 · 第六章](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md#六数据处理模块解耦rocketmq--http-直连) 与 [ADR-0002](../adr/0002-device-foundation-comm-hub-capability-bus.md)。本文档保留作为历史记录。

- **文档版本**：v1.0
- **日期**：2026-04-27
- **作者**：lorete
- **状态**：已确认，待实现（已被 HTTP 直连方案取代，见上方提示）

**RocketMQ 版本约定**（与父 `pom.xml` 一致）：`rocketmq-spring-boot-starter` 见 **`rocketmq-spring.version`**（**2.3.1**）；独立 Broker / NameServer 容器镜像 **`apache/rocketmq:${rocketmq-broker-docker.version}`**（**5.3.2**）。

---

## 一、背景与目标

realman-iot 平台需与外部达尔文数采平台对接，通过独立 RocketMQ Broker 实现异步消息交换。与现有 MQTT 体系完全独立，互不干扰。

**对接范围**：
1. 设备上下线状态推送（MQTT 接收后触发）
2. 接收达尔文工单请求并创建内部工单
3. OSS 文件上传授权申请与响应（达尔文不能直连 MinIO，通过本平台中转）
4. 文件地址上报（达尔文上传完成后通知本平台记录关联关系）

**已确认约束**：

| 问题 | 确认结论 |
|---|---|
| RocketMQ 部署 | 独立 Broker，通过 NameServer 互联 |
| 消息格式 | JSON，不加签名 |
| OSS 方案 | 达尔文不能直连 MinIO，需本平台 HTTP API 中转 |
| 工单字段映射 | 不与达尔文系统同步，单独维护映射关系（独立映射表） |
| 设备状态推送 | 仅推在线/离线事件，不推状态上报数据 |

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       realman-iot 服务                          │
│                                                                 │
│  MQTT 事件（现有，不变）                                         │
│  DeviceOnlineOfflineHandler                                     │
│       └──追加──→ DarwinDeviceStatusProducer                     │
│                         │                                       │
│  ┌──────────────────────┼──────────────────────────────────┐   │
│  │     RocketMQ 集成层（新增 darwin/ 包）                   │   │
│  │                      │                                   │   │
│  │  Producer                Consumer                        │   │
│  │  ├ DarwinDeviceStatus    ├ DarwinWorkOrderConsumer       │   │
│  │  └ DarwinOssAuthResponse ├ DarwinOssAuthRequestConsumer  │   │
│  │                          └ DarwinFileReportConsumer      │   │
│  │                                                          │   │
│  │  HTTP 中转（新增 Controller）                             │   │
│  │  └ DarwinFileUploadController                            │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                    ↕ NameServer 互联
              独立 RocketMQ Broker（达尔文侧运维）
                    ↕
            达尔文数采平台
```

---

## 三、Topic 与消费组设计

| Topic | 方向 | Tag | 说明 |
|---|---|---|---|
| `DARWIN_DEVICE_STATUS` | realman → Darwin | `ONLINE` / `OFFLINE` | 设备上下线事件 |
| `DARWIN_WORKORDER_IN` | Darwin → realman | `CREATE` | 达尔文下发工单 |
| `DARWIN_OSS_AUTH_REQUEST` | Darwin → realman | `REQUEST` | 申请文件上传 Token |
| `DARWIN_OSS_AUTH_RESPONSE` | realman → Darwin | `RESPONSE` | 返回上传 Token 与 URL |
| `DARWIN_FILE_REPORT` | Darwin → realman | `UPLOAD` | 上传完成后上报关联关系 |

- **Producer Group**：`REALMAN_IOT_PRODUCER_GROUP`
- **Consumer Group**：`REALMAN_IOT_CONSUMER_GROUP`（集群消费模式，多实例负载均衡）

---

## 四、新增模块结构

```
realman-boot-iot-biz/src/main/java/.../
└── darwin/
    ├── constant/
    │   └── DarwinTopicConstant.java        # Topic / Tag 常量
    ├── dto/
    │   ├── DarwinDeviceStatusDTO.java       # 设备状态推送消息体
    │   ├── DarwinWorkOrderCreateDTO.java    # 达尔文工单创建消息体
    │   ├── DarwinWorkOrderMappingDTO.java   # 工单字段映射（达尔文字段 → 内部字段）
    │   ├── DarwinOssAuthRequestDTO.java     # OSS 上传授权申请消息体
    │   ├── DarwinOssAuthResponseDTO.java    # OSS 上传授权响应消息体
    │   └── DarwinFileReportDTO.java         # 文件地址上报消息体
    ├── producer/
    │   ├── DarwinDeviceStatusProducer.java  # 推送设备上下线事件
    │   └── DarwinOssAuthResponseProducer.java
    ├── consumer/
    │   ├── DarwinWorkOrderConsumer.java     # 消费工单创建请求
    │   ├── DarwinOssAuthRequestConsumer.java
    │   └── DarwinFileReportConsumer.java
    └── service/
        └── DarwinUploadTokenService.java    # 上传 Token 生成与校验（Redis 存储）

realman-boot-iot-api/src/main/java/.../
└── controller/darwin/
    └── DarwinFileUploadController.java     # HTTP 文件上传中转接口
```

---

## 五、消息体设计

所有消息均携带 `traceId`，用于端到端链路追踪。字段命名采用 camelCase JSON。

### 5.1 设备上下线推送（realman → Darwin）

```json
{
  "traceId": "uuid",
  "deviceCode": "RM-001",
  "deviceType": "MASTER",
  "eventType": "ONLINE",
  "eventTime": "2026-04-27T10:00:00",
  "offlineReason": ""
}
```

- `eventType`：`ONLINE` / `OFFLINE`
- `offlineReason`：下线时填写（如 `KEEPALIVE_TIMEOUT`），上线时为空字符串

### 5.2 工单创建请求（Darwin → realman）

```json
{
  "traceId": "uuid",
  "darwinOrderId": "DW-20260427-001",
  "darwinAgentId": "DA-001",
  "darwinAgentName": "张三",
  "darwinDeptId": "DD-001",
  "darwinDeptName": "运维部",
  "taskName": "年度巡检",
  "planStartTime": "2026-05-01T09:00:00",
  "planEndTime": "2026-05-01T18:00:00",
  "deviceCodes": ["RM-001", "RM-002"],
  "unitPrice": "500.00",
  "remark": ""
}
```

- `darwinOrderId`：达尔文业务主键，用于幂等去重
- `darwinAgentId` / `darwinDeptId`：达尔文侧 ID，不映射为内部 ID，单独存入映射表

### 5.3 OSS 上传授权申请（Darwin → realman）

```json
{
  "traceId": "uuid",
  "correlationId": "uuid",
  "fileName": "inspection-photo.jpg",
  "fileSize": 2048000,
  "mimeType": "image/jpeg",
  "bizType": "WORKORDER_ATTACHMENT",
  "bizId": "WO-20260427-001"
}
```

- `bizType`：业务类型，限定范围 `WORKORDER_ATTACHMENT` / `DEVICE_FILE`
- `bizId`：关联的达尔文业务 ID（工单 ID 或设备 Code）

### 5.4 OSS 上传授权响应（realman → Darwin）

```json
{
  "traceId": "uuid",
  "correlationId": "uuid",
  "success": true,
  "uploadUrl": "https://realman-iot/darwin/file/upload",
  "uploadToken": "eyJhbGci...",
  "tokenExpireAt": "2026-04-27T11:00:00",
  "errorCode": "",
  "errorMsg": ""
}
```

- `uploadUrl`：本平台 HTTP 上传接口地址（非 MinIO 直链）
- `uploadToken`：一次性 Token，TTL 1 小时，存入 Redis

### 5.5 文件地址上报（Darwin → realman）

```json
{
  "traceId": "uuid",
  "darwinFileId": "uuid",
  "correlationId": "uuid",
  "workOrderId": "WO-20260427-001",
  "deviceCode": "RM-001",
  "fileType": "ATTACHMENT",
  "fileName": "inspection-photo.jpg",
  "fileUrl": "https://realman-iot/file/view/xxx",
  "fileSize": 2048000,
  "uploadTime": "2026-04-27T10:30:00"
}
```

- `correlationId`：与授权申请对应，用于追踪完整链路
- `workOrderId` / `deviceCode`：至少一个非空，用于关联内部业务对象

---

## 六、各业务场景详细流程

### 6.1 设备上下线状态推送

**改动点**：仅在 `DeviceOnlineOfflineHandler` 现有逻辑末尾追加发送，不修改现有任何逻辑。

```
DeviceOnlineOfflineHandler.handleOnline() / handleOffline()
  ├── [现有] 更新 DB、Redis、WebSocket 推送、操作日志
  └── [追加] DarwinDeviceStatusProducer.send(deviceCode, eventType, traceId)
                  ↓  try-catch 隔离，失败仅 WARN 日志，不影响主流程
              DARWIN_DEVICE_STATUS [ONLINE/OFFLINE]
                  ↓
              达尔文数采平台消费
```

### 6.2 接收达尔文工单并创建内部工单

```
达尔文平台
    ↓ DARWIN_WORKORDER_IN [CREATE]
DarwinWorkOrderConsumer.onMessage(DarwinWorkOrderCreateDTO)
    ├── 1. 幂等检查：SELECT FROM darwin_workorder_mapping WHERE darwin_order_id = ?
    │         存在 → 直接 ACK，跳过
    ├── 2. 校验 deviceCodes 在 iot_device 中存在，不存在的 Code 记录 WARN 跳过
    ├── 3. WorkOrderServiceImpl.createWorkOrderFromDarwin(dto)
    │         ├── 创建 work_order（status=PENDING, source=2）
    │         ├── 批量创建 work_order_device
    │         └── 写入 darwin_workorder_mapping（达尔文字段映射）
    └── 4. ACK

异常处理：
    - 业务校验失败（设备全不存在等）→ 不重试，消息入 DLQ，告警
    - DB/网络异常 → 抛异常触发 RocketMQ 重试（最大 3 次，间隔 1s/2s/5s）
```

### 6.3 OSS 文件上传（三段式中转）

```
第一段：申请 Token
达尔文平台
    ↓ DARWIN_OSS_AUTH_REQUEST [REQUEST]
DarwinOssAuthRequestConsumer.onMessage(DarwinOssAuthRequestDTO)
    ├── 1. 校验 bizType 白名单、mimeType 白名单、fileSize 上限（默认 50MB）
    ├── 2. DarwinUploadTokenService.generateToken(correlationId, dto)
    │         └── 生成 UUID Token，写入 Redis（Key=darwin:upload:token:{token}，TTL=1h）
    │             Value = {correlationId, bizType, bizId, fileName, mimeType, fileSize}
    └── 3. DarwinOssAuthResponseProducer.send(correlationId, uploadUrl, token)
              ↓ DARWIN_OSS_AUTH_RESPONSE [RESPONSE]
          达尔文收到 Token 和上传 URL

第二段：文件上传（HTTP 中转）
达尔文平台
    ↓ POST /realman-iot/darwin/file/upload
      Header: X-Upload-Token: {token}
      Body: multipart/form-data（file）
DarwinFileUploadController.upload(token, file)
    ├── 1. DarwinUploadTokenService.validateAndConsume(token) → 取出元数据，删除 Token（一次性）
    ├── 2. 校验文件 MIME、大小与 Token 中记录一致
    ├── 3. MinioUtil.upload(bucketName, objectKey, inputStream)
    ├── 4. 返回 { fileUrl: "https://...", internalFileId: "xxx" }
    └── 5. Redis 记录 {correlationId → internalFileId}（TTL=24h，供文件上报关联）

第三段：文件地址上报
    → 见 6.4
```

### 6.4 文件地址上报

```
达尔文平台上传完成后
    ↓ DARWIN_FILE_REPORT [UPLOAD]
DarwinFileReportConsumer.onMessage(DarwinFileReportDTO)
    ├── 1. 幂等检查：Redis SET NX darwin:file:report:{darwinFileId}（TTL=24h）
    │         已存在 → 直接 ACK
    ├── 2. 若 workOrderId 非空 → WorkOrderAttachmentService.saveExternalAttachment(dto)
    ├── 3. 若 deviceCode 非空 → IotDeviceService.saveExternalFile(dto)
    └── 4. ACK
```

---

## 七、DB 变更

### 7.1 work_order 表新增字段

```sql
ALTER TABLE work_order
  ADD COLUMN source TINYINT NOT NULL DEFAULT 1 COMMENT '来源 1=内部创建 2=达尔文平台',
  ADD INDEX idx_source (source);
```

### 7.2 新增达尔文工单映射表

```sql
CREATE TABLE darwin_workorder_mapping (
  id               BIGINT       NOT NULL COMMENT '主键',
  work_order_id    BIGINT       NOT NULL COMMENT '内部工单ID',
  darwin_order_id  VARCHAR(64)  NOT NULL COMMENT '达尔文工单ID',
  darwin_agent_id  VARCHAR(64)  NULL     COMMENT '达尔文操作员ID',
  darwin_agent_name VARCHAR(64) NULL     COMMENT '达尔文操作员姓名',
  darwin_dept_id   VARCHAR(64)  NULL     COMMENT '达尔文部门ID',
  darwin_dept_name VARCHAR(64)  NULL     COMMENT '达尔文部门名称',
  raw_message      TEXT         NULL     COMMENT '原始消息体（排查用）',
  del_flag         TINYINT      NOT NULL DEFAULT 0,
  create_by        VARCHAR(64)  NOT NULL,
  create_time      DATETIME     NOT NULL,
  update_by        VARCHAR(64)  NOT NULL,
  update_time      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE INDEX uk_darwin_order_id (darwin_order_id),
  INDEX idx_work_order_id (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='达尔文平台工单映射表';
```

### 7.3 回滚 SQL

```sql
DROP TABLE IF EXISTS darwin_workorder_mapping;
ALTER TABLE work_order DROP INDEX idx_source, DROP COLUMN source;
```

---

## 八、Nacos 配置（realman-iot.yaml）

```yaml
rocketmq:
  name-server: ${ROCKETMQ_NAME_SERVER:darwin-rocketmq-nameserver:9876}
  producer:
    group: REALMAN_IOT_PRODUCER_GROUP
    send-message-timeout: 3000
    retry-times-when-send-failed: 3
    access-key: ${ROCKETMQ_ACCESS_KEY:}
    secret-key: ${ROCKETMQ_SECRET_KEY:}
  consumer:
    group: REALMAN_IOT_CONSUMER_GROUP
    access-key: ${ROCKETMQ_ACCESS_KEY:}
    secret-key: ${ROCKETMQ_SECRET_KEY:}

darwin:
  integration:
    enabled: ${DARWIN_INTEGRATION_ENABLED:false}
    file-upload:
      max-file-size-mb: 50
      allowed-mime-types:
        - image/jpeg
        - image/png
        - video/mp4
        - application/pdf
      allowed-biz-types:
        - WORKORDER_ATTACHMENT
        - DEVICE_FILE
      upload-bucket: ${DARWIN_UPLOAD_BUCKET:darwin-files}
      upload-url-prefix: ${DARWIN_UPLOAD_URL_PREFIX:https://realman-iot/darwin/file}
```

> 凭据（`ROCKETMQ_ACCESS_KEY` / `SECRET_KEY`）通过 Nacos 或 Vault 注入，禁止硬编码。

---

## 九、依赖变更

在 `realman-boot-iot-biz/pom.xml`（或父 pom 版本管理）中新增：

```xml
<!-- 版本属性（父 pom properties） -->
<rocketmq-spring.version>2.3.1</rocketmq-spring.version>
<rocketmq-broker-docker.version>5.3.2</rocketmq-broker-docker.version>

<!-- realman-boot-iot-biz/pom.xml -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>${rocketmq-spring.version}</version>
</dependency>
```

> Spring Boot 3.x 对应 rocketmq-spring-boot-starter 2.3.x。  
> 独立部署 NameServer / Broker 时，Docker 镜像 `apache/rocketmq` 的 tag 必须与父 pom 中 **`rocketmq-broker-docker.version`**（当前 **5.3.2**）一致。

---

## 十、集群安全评估

| 风险项 | 影响 | 处理方案 |
|---|---|---|
| Consumer 多实例重复消费工单 | 创建重复工单 | `darwin_workorder_mapping.uk_darwin_order_id` DB 唯一键 + 消费前查重 |
| Consumer 多实例重复处理文件上报 | 重复写附件记录 | Redis `SET NX darwin:file:report:{darwinFileId}`（TTL=24h） |
| 上传 Token 被多次使用 | 恶意或误重传 | `validateAndConsume` 原子删除 Token，一次性有效 |
| Producer 发送失败 | 状态漏推 | 失败仅记录 WARN + Micrometer 计数器，不影响主流程；如需补偿后续可加 DB 重试队列 |
| RocketMQ Broker 不可用 | 所有消息中断 | 业务降级：MQTT 主流程不受影响；集成功能暂停，告警通知 |

**新增内容均无本地状态**，所有幂等标记存 Redis，完全符合集群化约束。

---

## 十一、可观测性

```
Micrometer Counter：
  darwin.device.status.sent{result="success/fail"}
  darwin.workorder.created{result="success/fail/duplicate"}
  darwin.oss.token.issued{biz_type="..."}
  darwin.file.uploaded{result="success/fail"}
  darwin.file.reported{result="success/fail/duplicate"}

DLQ 告警：
  Topic %DLQ%REALMAN_IOT_CONSUMER_GROUP 有消息 → 立即告警
```

---

## 十二、实施步骤

| 优先级 | 步骤 | 说明 |
|---|---|---|
| P0 | 引入 rocketmq-spring-boot-starter，Nacos 配置 RocketMQ 连接 | 基础前置 |
| P0 | 实现 Topic 常量、全部 DTO | 消息契约 |
| P0 | 实现 DarwinDeviceStatusProducer，接入 DeviceOnlineOfflineHandler | 最高频业务 |
| P0 | DB 变更（work_order + darwin_workorder_mapping） | 工单基础 |
| P0 | 实现 DarwinWorkOrderConsumer + createWorkOrderFromDarwin() | 核心业务 |
| P1 | 实现 DarwinUploadTokenService（Redis Token 管理） | OSS 前置 |
| P1 | 实现 DarwinOssAuthRequestConsumer + DarwinOssAuthResponseProducer | OSS 授权 |
| P1 | 实现 DarwinFileUploadController（HTTP 中转 + MinIO 写入） | 文件上传 |
| P1 | 实现 DarwinFileReportConsumer | 文件关联 |
| P2 | 补充 Micrometer 指标、DLQ 告警 | 可观测性 |

---

## 十三、上线检查清单

- [ ] 设计文档已归档 `docs/design/`（本文件）
- [ ] DB 正向 + 回滚脚本已准备
- [ ] RocketMQ Topic 已在达尔文侧创建，Consumer Group 权限已配置
- [ ] Nacos 配置已添加（`DARWIN_INTEGRATION_ENABLED=false`，灰度验证后再开启）
- [ ] 凭据通过 Nacos/Vault 注入，无硬编码
- [ ] 文件上传 MIME / 大小校验已覆盖
- [ ] 幂等逻辑已验证（工单重复消费、文件重复上报）
- [ ] 集群多实例并发消费测试通过
- [ ] Micrometer 指标已接入 Grafana 面板
- [ ] DLQ 告警已配置
