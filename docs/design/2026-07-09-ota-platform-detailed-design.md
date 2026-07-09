# OTA 平台详细设计（对齐《达尔文设备升级平台 PRD V1.0.0》原文）

| 项 | 内容 |
| --- | --- |
| **文档版本** | v1.0（已对照 PRD 原文逐项核对，替换 v0.1 草案）|
| **日期** | 2026-07-09 |
| **状态** | 提议 / 待评审 |
| **上级文档** | [V2 主设计文档](./2026-07-07-darwin-platform-v2-capability-bus-and-comm-hub.md) 第九章 Phase 3 |
| **姊妹文档** | [设备基座详细设计](./2026-07-08-device-foundation-detailed-design.md)、[设备通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md) |
| **依据输入** | 《达尔文设备升级平台 PRD V1.0.0》原文（用户提供）|

---

## 一、定位与本次修订说明

v0.1 草案在无法访问 PRD 原文的情况下，只能依据主设计文档 2.3 节的差距分析做二次推导，很多关键细节（15 态迁移矩阵、错误码枚举、17 项系统设置）只能标记为开放问题。**本版已通读 PRD 原文**，替换全部推导性内容为 PRD 原文的准确转译，同时保留本次架构升级已经确立的关键决策：**设备侧协议统一为 MQTT（自注册除外），PRD 里"设备直连 OTA 后台 HTTP 接口"的部分，一律通过设备通信中台归一化/桥接落地，不改变设备侧协议**（见第二章，这是本文档与 PRD 原文最大的一处主动偏离，其余内容均忠实转译）。

**本次核对最重要的发现**：PRD 第九章定义的"设备注册（9.8.4）/ 注册凭证生成（9.8.5-9.8.6）/ Token 签发续签吊销（9.7.5-9.7.7）/ 测试标记（9.8.2-9.8.3）"这一整套能力，**已经在设备基座 `realman-device-mgmt` 落地**，字段设计高度吻合（Device Token 365 天默认有效期、到期前 30 天续签、旧 Token 1 小时宽限期、注册凭证 365 天有效期、超管跨租户 `X-Operator-Tenant-Id`、is_test_device 二次确认防绕过）。**OTA 平台不重新实现这套能力，直接复用设备基座**——这是本文档相对 PRD 原文最大的架构简化，第七章详细说明复用边界与需要补齐的差距（主要是频率限制两个错误码尚未实现）。

---

## 二、协议映射：PRD 的"设备直连 HTTP"如何落地为 MQTT

PRD 原文假设设备（master/slave）直接通过 HTTP 接口与 OTA 后台通信（心跳、进度推送、状态补传、资源探测、Token 续签）。本次架构升级的既定决策是设备侧协议统一为 MQTT（[ADR-0002](./../adr/0002-device-foundation-comm-hub-capability-bus.md)、[通信中台详细设计](./2026-07-08-device-comm-hub-detailed-design.md)），因此下表是 PRD 接口到 MQTT Topic 的等价映射，OTA 平台自身只消费通信中台归一化后的 `DeviceUplinkEvent`，不直接暴露/消费这些 HTTP 接口：

| PRD 接口（原文语义） | 方向 | MQTT Topic（落地形态） | 归一化后的 `DeviceUplinkEvent.eventKind` |
| --- | --- | --- | --- |
| `POST /api/v1/devices/heartbeat`（9.7.1，含资源信息）| 上行 | `device/{code}/ota/heartbeat`（`CommHubTopicConstants.TOPIC_OTA_HEARTBEAT`，已预留）| `HEARTBEAT` |
| `POST /api/v1/ota/tasks/{id}/progress-push`（9.7.3，实时推送）| 上行 | `device/{code}/ota/progress`（`TOPIC_OTA_PROGRESS`，已预留）| `OTA_PROGRESS` |
| `POST /api/v1/ota/tasks/{id}/status-report`（9.7.2，离线批量补传）| 上行 | `device/{code}/ota/status-report`（`TOPIC_OTA_STATUS_REPORT`，已预留）| `OTA_STATUS_REPORT` |
| `POST /api/v1/devices/token/refresh`（9.7.6）| 上行（携带旧 Token）+ 下行（回传新 Token）| `device/{code}/ota/token-refresh`（`TOPIC_OTA_TOKEN_REFRESH`，已预留）| `TOKEN_REFRESH` |
| `POST /api/v1/devices/{id}/resource-probe`（9.7.4，operate=5，OTA 主动探测）| 下行（OTA 发起）+ 上行（设备回执）| 通信中台统一下行发布 `topicSuffix="ota/resource-probe"`（**本文档新增 Topic，非 PRD 已有映射**，见下方 2.1），`waitAck=true` | 不产生 `DeviceUplinkEvent`（属于同步 publish-and-wait，不是异步上行事件）|
| `POST /api/v1/devices/register`（9.8.4，一次性）| 上行（南向唯一 HTTP 例外）| 不变，仍是 HTTP：`POST /internal/device/provision`（已实现，见设备通信中台 3.1）| `REGISTER` |
| 下行任务通知（PRD 未单独编号，含固件下载地址/签名信息）| 下行 | `device/{code}/ota/notify`（`TOPIC_OTA_NOTIFY`，已预留）| — |

### 2.1 新增 Topic：`ota/resource-probe`

PRD 9.7.4 的资源探测是 OTA 后台在创建任务前**主动发起**的同步请求-响应（"若设备端不支持则返回 503，回退心跳基础值"），这与统一下行发布的 `publish-and-wait` 模式完全吻合，不需要新建协议——只需要在 `CommHubTopicConstants` 补一个 Topic 后缀常量（`TOPIC_OTA_RESOURCE_PROBE = "ota/resource-probe"`），OTA 平台调用 `CommHubFeignClient.publish(deviceId, "ota/resource-probe", payload, waitAck=true, ackTimeoutMs=5000)`，超时或 `TIMEOUT` 状态等价于 PRD 的"设备端不支持 operate=5，返回 503"分支，OTA 侧按相同的心跳基础值回退降级处理。

### 2.2 Device Token 在 MQTT 报文里的位置

PRD 假设 Token 通过 `Authorization: Bearer` 请求头携带。MQTT 报文没有 HTTP 头的概念，落地方式沿用设备基座详细设计 3.3 已确立的约定：**Device Token 作为业务报文 payload 内的 `device_token` 字段随每条上行消息携带**，由通信中台在归一化为 `DeviceUplinkEvent` 之前调用 `DeviceMgmtFeignClient.validateToken` 完成校验；校验失败（`ERR_TOKEN_REVOKED`/`ERR_TOKEN_EXPIRED`）时通信中台直接拦截，不产生 `DeviceUplinkEvent`，OTA 平台看到的都是已通过 Token 校验的合法事件——**OTA 平台不需要自己再校验一次 Token**。

---

## 三、设备角色与固件模型（对齐 3.1-3.3、4.2.1）

### 3.1 设备角色

只有两类：`master`（遥操设备）、`slave`（机器人），二者是独立设备节点，互不依赖，同一时刻每台设备只允许一个升级任务，master 升级不影响 slave（反之亦然）。对齐设备基座 `DeviceType` 枚举：`MASTER`/`SLAVE`（`SMART_ARM` 是设备基座为其他项目预留的第三类型，不在本次 OTA PRD 范围内，OTA 平台本期不支持 Smart Arm 组件升级）。

### 3.2 `ota_firmware`（固件包）

**重要修正**：PRD 明确"固件包不区分租户归属，所有有权限用户均可查看"——移除 v0.1 草案里的 `tenant_id` 字段。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `package_id` | varchar(36), PK | 上传接口生成的 UUID |
| `firmware_file_name` | varchar(256) | 原始文件名（如 `rm_master_v1.0.4.tar.gz`）|
| `device_type` | varchar(16) | `master` / `slave` |
| `version` | varchar(32) | 从文件名解析并规范化为大写 V 格式存储（`v1.0.4` → `V1.0.4`），语义化版本三段比较（见 3.4）|
| `min_version` | varchar(32)，可空 | 最低可升级版本；为空跳过版本号比对（型号校验仍执行）|
| `compatible_models` | json (string[]) | 适配型号列表；为空数组视为全型号 |
| `install_command` / `rollback_command` / `healthcheck_command` | varchar(512)，均可空 | 为空时设备端回退默认逻辑 |
| `risk_level` | varchar(16) | `normal`（默认）/ `high_risk` |
| `cancelable_in_executing` | boolean | 默认 `false`；`true` 时 `install_command` 支持 `SIGTERM` 安全中断 |
| `sha256` | varchar(64) | 上传时系统自动计算 |
| `sig_oss_path` / `sig_local_path` | varchar(512)，二选一 | `.sig` 签名文件路径，与固件包同源（OSS 或本地盘）|
| `key_id` | varchar(36) | 上传成功时自动关联当前 `active` 公钥 |
| `storage_source` | varchar(16) | `LOCAL` / `OSS` |
| `file_size_mb` | int | 单位 MiB（1 MiB = 1,048,576 字节），上限 `max_firmware_size_mb`（默认 2048）|
| `created_by` / `created_at` | — | — |

**校验规则（4.2.1）**：文件名须含设备角色字段与版本号；`.sig` 文件须同时上传（Base64 解码后固定 64 字节，否则 `ERR_SIG_FORMAT_INVALID`；缺失 `ERR_SIG_FILE_MISSING`）；超过 `max_firmware_size_mb` 返回 413 `ERR_FIRMWARE_TOO_LARGE`；版本号格式非法（非"大写V+三段非负整数"）返回 400 `ERR_INVALID_VERSION_FORMAT`。

### 3.3 固件包来源与本地盘 `.sig` 扫描（4.2.5）

| 来源 | 扫描接口 | 说明 |
| --- | --- | --- |
| 本地盘（U 盘）| `GET /api/v1/firmware/local-scan?operate=0` | 默认路径 `/tmp`、`/media/realman` 及子目录 `ota/`；同名包不同路径全部返回，不去重；同步返回 `sig_available` |
| OSS/S3 | `GET /api/v1/firmware/local-scan?operate=1` | OSS 凭证（`oss_endpoint`/`oss_bucket`/`oss_access_key_id`/`oss_access_key_secret`/`oss_region`）在系统设置中配置，加密存储，扫描接口调用方无需传凭证 |

`sig_available=false` 时禁止基于该包创建升级任务，提示"本地盘签名文件缺失"。

### 3.4 版本号规范

统一大写 `V` 开头，`V大版本.小版本.修订号`（如 `V1.0.0`）；比较采用语义化版本规则（三段分别按整数比较，`V1.10.0 > V1.9.0`），存储前做合法性校验（三段均非负整数），非法格式 `ERR_INVALID_VERSION_FORMAT`。

---

## 四、密钥生命周期（对齐 4.2.2）

私钥（`signing_key`）由固件发布方离线持有（建议 HSM 或 age/GPG 加密文件），**不上传平台**；平台只管理公钥（`verification_key`）。

### 4.1 `ota_key`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key_id` | varchar(36), PK | UUID |
| `algorithm` | varchar(16) | 固定 `Ed25519`（上传时校验 PEM 格式 + 算法类型，不支持 RSA/ECDSA）|
| `public_key_pem` | varchar(512) | 标准 32 字节公钥，PEM 格式，上限 1KB |
| `key_fingerprint` | varchar(64) | SHA-256 指纹前 32 位十六进制（128 bit）|
| `key_alias` | varchar(64)，可空 | — |
| `status` | varchar(24) | `active` / `pending_activation` / `revoked` |
| `activated_at` / `revoked_at` | datetime，可空 | — |
| `revoke_reason` | varchar(128)，可空 | — |
| `created_by` / `created_at` | — | — |

### 4.2 状态语义

| 状态 | 允许下发新固件包 | 说明 |
| --- | --- | --- |
| `active` | ✔ | 同一时刻只有一个 `active`，新上传固件包自动关联 |
| `pending_activation` | ✘ | 轮换过渡期，禁止以新 `key_id` 关联任何固件包下发升级任务，直至超管手动激活完成轮换 |
| `revoked` | ✘ | 关联此公钥的固件包全部无法下发新任务；**已下发正在执行的任务不中断**；`revoked` 公钥保留 180 天归档后删除 |

**轮换流程**：超管上传新公钥（`pending_activation`）→ 研发团队通过固件升级将新公钥预置到设备只读分区 → 超管确认预置完成后调用激活接口（原 `active` 自动转 `revoked`，新公钥转 `active`）。**紧急吊销**：私钥疑似泄露时超管立即吊销当前 `active` 公钥（需输入确认文本 `REVOKE` + 二次确认），关联固件包的 `PENDING` 状态任务立即置 `FAILED`（`ERR_KEY_REVOKED`），已下发执行中任务不中断。

### 4.3 全局签名校验开关（`global_sig_verify_enabled`，默认 `true`）

仅用于设备端尚未全量预置新公钥的过渡期临时关闭**设备端**签名验证。关闭需输入确认文本 `DISABLE_SIG_VERIFY` + 二次确认；关闭期间**每次创建任务**仍需超管再输入 `CONFIRM_SIG_SKIP` 二次确认，并在审计日志写入 `sig_verify_skipped=true`（`audit_level=critical`）；**平台侧的签名吊销校验（`ERR_KEY_REVOKED`）不受此开关影响**，任务创建时和下发前仍正常执行公钥状态检查。关闭状态下全平台展示橙色警告横幅；升级成功日志标注 `sig_verified=false`，不得与正常签名成功日志混淆。

### 4.4 双重校验时点

签名吊销校验与版本兼容性校验（见六.3）都在两个时间点各执行一次：**创建任务时**（第一次）与 **STARTING 阶段下发前**（第二次，防止创建到下发之间窗口期内公钥被吊销/设备版本发生变化）。

---

## 五、升级任务与批量策略（对齐 4.4）

### 5.1 四种升级方式

| 方式 | 目标 | 约束 |
| --- | --- | --- |
| `by_sn` | 单台设备 | master/slave 均支持；校验 SN 归属所选 `device_type` |
| `by_model` | 同机型全部在线设备 | 单次目标数不超过 `max_batch_devices`（默认 1000），超出 `ERR_BATCH_DEVICE_LIMIT_EXCEEDED`；禁止 `high_risk` 包 |
| `all` | 指定设备类型下全部机型全部在线设备 | 同上限制；禁止 `high_risk` 包 |
| `by_tenant_model` | 指定租户+机型全部在线设备 | 运维人员只能对本租户发起（后端校验 `tenant_id==操作者租户`，不一致 403）；超管可跨租户但须带 `X-Operator-Tenant-Id`；禁止 `high_risk` 包 |

`high_risk` 包**只允许 `by_sn` 下发给已标记 `is_test_device=true` 的设备**，其余三种方式一律拒绝（`ERR_HIGH_RISK_RESTRICTED`）。

`device_type` 是唯一对外暴露的目标类型字段，后端内部映射具体升级目标角色，**不接受/不暴露 `target` 字段**。

### 5.2 批量失败策略

| 字段 | 说明 |
| --- | --- |
| `fail_threshold_type` | `count`（按台数，默认）/ `percent`（按百分比，向上取整：`ceil(总数 × 阈值%)`）|
| `fail_threshold` | 对应数值；`count` 模式下 `0` 表示永不触发（等价于 `continue`）|
| `on_threshold_exceeded` | `pause`（默认，暂停等待人工决策）/ `stop_all`（终止未下发子任务，已下发继续跑完）/ `continue`（忽略失败继续下发）|

未传时使用全局默认值（系统设置：`default_fail_threshold_type=count`、`default_fail_threshold=5`、`default_on_threshold_exceeded=pause`）。

**`stop_all` 语义**：只把**尚未下发**的子任务置 `CANCELLED`；已下发的进行中子任务（`DOWNLOADING`/`CHECKING`/`EXECUTING`/`ROLLING_BACK` 等）继续执行至终态。执行期间批量任务整体 `status` 仍为 `IN_PROGRESS`，同时置位 `stop_all_triggered=true`（UI 显示橙色"系统中止中"），全部子任务到达终态后标志位清除、整体进入最终状态。

**PAUSED 任务的 resume/abort**：
- `resume`：向剩余未下发设备继续推送；可在 resume 时更新 `fail_threshold`；每次 resume 持久化 `active_fail_threshold_snapshot`（传新值更新、不传冻结、传 `null` 重置为创建时初始值）。
- `abort`：等价于对 PAUSED 任务触发 `stop_all`，用**最后一次生效的 `active_fail_threshold_snapshot`** 判定最终态：
  - `completed/(total-cancelled) ≥ 100%` → `COMPLETED`
  - 否则 `failed ≥ active_fail_threshold_snapshot` → `FAILED`
  - 否则 → `PARTIAL_COMPLETED`
  - `total-cancelled=0`（全部取消）→ `CANCELLED`

**完成率计算**：正常执行中分母为批次总数；abort 后分母切换为 `total-cancelled`（`completion_rate_basis` 字段标注 `total`/`executed`，UI 展示切换提示）。

### 5.3 任务唯一性/幂等

幂等键 = `task_id + package_id` 二元组（`package_id` 全局唯一且与版本号强绑定，`target_version` 是冗余字段不纳入幂等键，避免版本号格式差异导致幂等失效）。设备重复拉取同一任务不重复安装，上报 `ERR_DUPLICATE_TASK`；重新创建任务（新 `task_id`）是合法新任务，不受此约束。

---

## 六、前置校验（四类，对齐 4.4.2）

### 6.1 设备状态检查

| 状态 | 是否允许 | 备注 |
| --- | --- | --- |
| `IDLE` | ✔ | — |
| `UPGRADING` / `TELEOP_ACTIVE` / `TASK_RUNNING` | ✘（软禁止）| 提示并拒绝 |
| `OFFLINE` | ✘ | 不算前置校验失败，走 `PENDING_ONLINE` 排队等待上线 |
| `MASTER_FAULT`（master 专用）/ `EMERGENCY_STOP`/`FAULT`（slave 专用）/ `MOVING`（两者通用）| ✘（硬禁止）| 强制禁止，`ERR_PRECONDITION_FAILED` |

设备状态语义复用设备基座 `occupancy_state`/`online_status`，`MASTER_FAULT`/`EMERGENCY_STOP`/`FAULT`/`MOVING`/`TELEOP_ACTIVE`/`TASK_RUNNING`/`UPGRADING` 这组更细的 OTA 专属可升级性判断，是在设备基座四态之上的**业务层叠加语义**，不需要 SSOT 新增字段——OTA 平台读取 SSOT 的 `occupancy_state`/`occupancy_detail` 后按自己的规则表映射到这组状态（映射规则本身是 OTA 平台私有逻辑，双方契约只到"占用状态"这一层）。

### 6.2 设备资源检查

| 维度 | 规则 | 不满足处理 |
| --- | --- | --- |
| 磁盘空间 | 可用空间 ≥ 固件包大小 × 2（暂存目录 + releases 新版本目录，不计入待清理的旧目录）| 拒绝，展示可用/所需数值 |
| 电源状态 | `normal`（非 `low_battery`/`power_fault`）| 拒绝 |
| 内存 | 可用内存 ≥ 阈值（默认 256 MiB，设备端配置）| 拒绝 |
| 网络可用性 | 优先主动探测（`ota/resource-probe`，见 2.1）；探测不支持时回退最近心跳 `network_reachable`，需在 `network_valid_seconds`（默认 300s）有效期内 | 不可达/数据过期均拒绝，提示对应文案 |
| CPU 负载 | 5 分钟平均负载 ≤ 阈值（默认 80%，设备端配置）| 仅警告，不强制拒绝 |

资源数据来自设备心跳（`ota/heartbeat` 归一化的 `HEARTBEAT` 事件），各维度独立配置有效期，超期标注"数据可能过期"。

### 6.3 版本兼容性校验（双重）

`min_version` 非空时要求设备当前版本 ≥ `min_version`；`compatible_models` 非空时要求设备型号在列表中；两者可独立跳过。创建任务时校验一次，`STARTING` 阶段下发前再校验一次（防止窗口期内设备版本变化），第二次不满足直接 `FAILED` + `ERR_VERSION_INCOMPATIBLE`，不自动重试。

### 6.4 签名吊销校验（双重）

同上双重校验节奏，检查固件包关联公钥状态：`active` 放行，`pending_activation`/`revoked` 一律拒绝（`ERR_KEY_REVOKED`）。

---

## 七、与设备基座的复用边界（本文档相对 PRD 最大的架构简化）

PRD 第九章大量篇幅（9.7.5-9.7.7、9.8.2-9.8.6）定义的设备注册、Token 签发/续签/吊销、测试标记能力，**已经在 `realman-device-mgmt`/`realman-device-info` 完整实现**，字段与流程高度吻合：

| PRD 能力 | 已实现位置 | 核对结果 |
| --- | --- | --- |
| 设备注册（9.8.4，含一次性 `device_registration_secret`）| `DeviceMgmtServiceImpl#provision` | 字段一致：`deviceCode`/`deviceType`/`tenantId`/`deviceRegistrationSecret` |
| 注册凭证生成/查询（9.8.5-9.8.6，默认 365 天有效期）| `DeviceAdminServiceImpl#generateRegistrationSecret`/`getRegistrationSecretStatus` | 完全一致，含"仅返回一次明文" |
| Device Token 签发/续签/吊销（9.7.5-9.7.7，365 天默认、到期前 30 天续签、旧 Token 1 小时宽限期）| `DeviceMgmtServiceImpl#issueToken`/`DeviceAdminServiceImpl#refreshToken`/`revokeToken` | 完全一致（宽限期通过"不比对 jti"的设计隐式实现，见 device-mgmt 提交说明）|
| 测试标记 + 二次确认防绕过（4.1.1/9.8.2-9.8.3）| `DeviceAdminServiceImpl#updateTestFlag`/`batchUpdateTestFlag` | 一致；**高风险任务前置校验目前是已知缺口**（见下）|
| 超管跨租户 `X-Operator-Tenant-Id` + 双 `tenant_id` 审计 | `RequestUtil#operatorTenantId` + `writeAudit` | 一致 |

**OTA 平台不重新实现以上能力**，只做两件事：
1. 需要设备身份/在线状态/资源信息时，走 `DeviceInfoFeignClient`/`DeviceMgmtFeignClient`（同设备基座、通信中台的一贯做法），不自建设备表。
2. 提供一个内部只读接口 `GET /internal/ota/devices/{deviceId}/active-high-risk-task`（`HighRiskTaskController`），供 `DeviceAdminServiceImpl#updateTestFlag` 取消标记时前置校验回调——这是设备基座文档 3.5 时序图里"查 OTA"这一步，**已在本轮补齐**：`device-mgmt-biz` 新增 `realman-boot-ota-contract` 依赖，`updateTestFlag` 在 `testDevice=false` 分支实际调用 `OtaFeignClient#getActiveHighRiskTask`，OTA 不可达时 Feign fallback 保守返回 `hasActiveTask=true`（拒绝取消）。

**已补齐的设备基座差距**：
- `ERR_REGISTER_RATE_LIMIT`（同一 SN 每小时最多注册 5 次）、`ERR_SECRET_GENERATE_RATE_LIMIT`（同一 SN 每小时最多生成 10 次凭证）已在 `device-mgmt-biz` 新增 `DeviceRateLimitService`（Redis `INCR`+`EXPIRE` 固定窗口限流，Redis 不可用时保守放行）落地，分别接入 `DeviceMgmtServiceImpl#provision`、`DeviceAdminServiceImpl#generateRegistrationSecret`。

---

## 八、升级状态机（15 态，完整迁移规则）

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已创建，等待下发（在线设备）|
| `PENDING_ONLINE` | 目标设备离线，等待上线；超过 `device_offline_timeout_hours`（默认 72h）自动 `FAILED` |
| `STARTING` | 已下发指令，执行下发前二次校验（版本兼容性 + 签名吊销）|
| `DOWNLOADING` | 下载中（HTTP Range 断点续传）|
| `CHECKING` | SHA-256 + Ed25519 签名校验，完成后上报 `sig_verify_result`（`pass`/`fail`/`skipped`）|
| `EXTRACTING` | 解压至 `releases/{version}/` |
| `EXECUTING` | 三个子阶段：`install_exec` → `symlink_switch` → `os_sync`，见 8.1 |
| `HEALTH_CHECKING` | 执行 `healthcheck_command` |
| `COMPLETED` | 成功终态 |
| `FAILED` | 失败终态（不触发回滚，设备归 `IDLE`）|
| `ROLLING_BACK` | 回滚中 |
| `ROLLED_BACK` | 回滚成功终态 |
| `ROLLBACK_FAILED` | 回滚失败终态，需人工介入 |
| `PAUSED` | 批量任务因阈值触发暂停，等待人工 resume/abort |
| `CANCELLED` | 已取消终态 |

`IN_PROGRESS` **不是**独立状态值，是批量任务 `status` 字段在正常执行/`stop_all` 执行期间的聚合值，不应出现在单设备任务的 `status` 字段。

### 8.1 EXECUTING 子阶段与差异化失败路径

| 子阶段 | 失败时 `current` 软链接状态 | 终态 |
| --- | --- | --- |
| `install_exec`（执行 `install_command`）| 未切换 | `FAILED`（不触发回滚）|
| `symlink_switch`（切换软链接）| 未切换（切换本身失败）| `FAILED`（不触发回滚）|
| `os_sync`（执行 `os.sync` 落盘）| **已切换** | `ROLLING_BACK`（软链接已指向新版本，必须回滚）|

设备端上报 `ERR_INSTALL_FAILED` 时须同时上报 `sub_stage` 字段，后台据此判断走哪条路径。此规则 master/slave 一致。

### 8.2 其他失败/取消路径

- `DOWNLOADING`/`CHECKING`/`EXTRACTING` 失败 → `FAILED` 兜底，不进回滚链路。
- `HEALTH_CHECKING` 失败（含超时 `healthcheck_timeout_seconds`，默认 60s）→ `ROLLING_BACK` → `ROLLED_BACK`/`ROLLBACK_FAILED`。
- 取消（`EXECUTING` 前，即 `PENDING`/`PENDING_ONLINE`/`DOWNLOADING`/`CHECKING`/`EXTRACTING`）：下发 cancel 指令，设备清理暂存目录，归 `IDLE`。
- 取消（`EXECUTING` 中）：仅 `cancelable_in_executing=true` 允许；设备端须上报 `symlink_switched`：`false` → `CANCELLED`+`IDLE`；`true` → 走 `ROLLING_BACK`。**兜底**：设备端因网络异常未上报 `symlink_switched`，等待 `cancel_ack_timeout`（默认 60s，10~300s 可配）后按保守原则视为 `true`，强制进入 `ROLLING_BACK`（宁可多回滚一次）。
- 手动回滚幂等：设备处于 `ROLLING_BACK`（自动回滚中）时再次调用手动回滚接口返回 409 `ERR_ROLLBACK_IN_PROGRESS`。

---

## 九、错误码完整清单（对齐 4.6.2，30 个）

| 错误码 | 触发场景 |
| --- | --- |
| `ERR_DOWNLOAD_FAILED` | 下载失败（网络/URL 过期）|
| `ERR_CHECKSUM_MISMATCH` | SHA-256 不匹配 |
| `ERR_SIGNATURE_INVALID` | Ed25519 签名验证失败 |
| `ERR_KEY_REVOKED` | 关联公钥 `revoked`/`pending_activation` |
| `ERR_EXTRACT_FAILED` | 解压失败 |
| `ERR_INSTALL_FAILED` | 安装失败（需同时上报 `sub_stage`）|
| `ERR_HEALTH_CHECK_FAILED` | 健康检查失败 |
| `ERR_HEALTH_CHECK_TIMEOUT` | 健康检查超时 |
| `ERR_ROLLBACK_FAILED` | 回滚失败 |
| `ERR_ROLLBACK_IN_PROGRESS` | 自动回滚进行中时触发手动回滚（409）|
| `ERR_STATUS_REPORT_FAILED` | 状态上报失败（网络恢复后自动补传）|
| `ERR_PRECONDITION_FAILED` | 设备状态检查失败（不含 OFFLINE）|
| `ERR_RESOURCE_INSUFFICIENT` | 资源检查失败 |
| `ERR_VERSION_INCOMPATIBLE` | 版本兼容性校验失败（含二次校验）|
| `ERR_DUPLICATE_TASK` | `task_id+package_id` 重复拉取 |
| `ERR_HIGH_RISK_RESTRICTED` | `high_risk` 包用了非 `by_sn` 或非测试设备 |
| `ERR_URL_EXPIRED` | OSS 预签名 URL 过期 |
| `ERR_BATCH_THRESHOLD_EXCEEDED` | 批量任务失败阈值触发 |
| `ERR_CONFIG_CONFLICT` | 系统设置项冲突（如心跳间隔 ≥ 资源有效期）|
| `ERR_SIG_FILE_MISSING` | 上传/本地盘扫描缺 `.sig` 文件 |
| `ERR_SIG_FORMAT_INVALID` | `.sig` 非法 Base64 或长度非 64 字节 |
| `ERR_PENDING_DISPATCH_TIMEOUT` | 在线设备 `PENDING` 下发超时（默认 30 分钟）|
| `ERR_FIRMWARE_TOO_LARGE` | 固件包超 `max_firmware_size_mb`（413）|
| `ERR_BATCH_DEVICE_LIMIT_EXCEEDED` | 批量目标数超 `max_batch_devices`（400）|
| `ERR_INVALID_VERSION_FORMAT` | 版本号格式非法（400）|
| `ERR_NOT_CANCELABLE` | `EXECUTING` 阶段但 `cancelable_in_executing=false`（409）|
| `ERR_INVALID_STATE` | retry/cancel/rollback 等操作时任务状态不允许（409）|
| `ERR_TOKEN_REVOKED` | Device Token 已吊销（401，复用设备基座）|
| `ERR_DEVICE_NOT_AUTHORIZED` | 注册凭证校验失败或 SN 未授权（400，复用设备基座）|
| `ERR_REGISTER_RATE_LIMIT` | 注册频率超限（429，5 次/小时，**设备基座待实现**）|
| `ERR_SECRET_GENERATE_RATE_LIMIT` | 凭证生成频率超限（429，10 次/小时，**设备基座待实现**）|

`upgrade_error_code` + `upgrade_error_msg` 必须同时上报，禁止只返回通用"升级失败"。

---

## 十、系统设置（17 项，对齐 9.9）

| 字段 | 默认值 | 校验规则 |
| --- | --- | --- |
| `disk_valid_seconds` | 300s | 须 > `heartbeat_interval_seconds` |
| `memory_valid_seconds` | 300s | 同上 |
| `power_valid_seconds` | 300s | 同上 |
| `network_valid_seconds` | 300s | 同上 |
| `pending_url_check_interval` | 15min | 建议 < `oss_url_expiry_seconds`/10 |
| `oss_url_expiry_seconds` | 86400s（24h）| ≥ 3600s 且 ≥ `pending_url_check_interval`×10 |
| `poll_interval_seconds` | 30s | 建议 10-120s |
| `push_exempt_seconds` | 30s | 建议 10-120s |
| `device_offline_timeout_hours` | 72h | ≥ 1h |
| `pending_online_device_timeout_minutes` | 30min | 1min ~ `device_offline_timeout_hours`×60 |
| `cancel_ack_timeout` | 60s | 10~300s |
| `device_token_expiry_days` | 365d | 30~3650d（**设备基座 `DeviceTokenProperties.expiryDays` 已实现同名语义**）|
| `registration_secret_expiry_days` | 365d | 1~3650d（**设备基座 `DeviceRegistrationProperties.secretExpiryDays` 已实现**）|
| `default_fail_threshold_type` | `count` | `count`/`percent` |
| `default_fail_threshold` | 5 | ≥0，`count` 时 ≤ `max_batch_devices` |
| `default_on_threshold_exceeded` | `pause` | `pause`/`stop_all`/`continue` |
| `heartbeat_interval_seconds` | 60s | ≥10s 且严格 < 全部资源有效期最小值 |

**`ERR_CONFIG_CONFLICT` 统一校验入口**：`POST /api/v1/system/config/validate`，所有配置修改（UI/API）都必须过这道校验。

---

## 十一、对外 API（对齐第九章，仅列管理端；设备端语义已在第二章转译为 MQTT）

| 分类 | 接口 | 权限 |
| --- | --- | --- |
| 固件管理 | `POST /api/v1/firmware/packages`（上传，9.1.1）、`GET /api/v1/firmware/local-scan?operate=0\|1`（9.1.2）、固件包删除（4.2.4 前置引用检查）| 运维人员及以上 |
| 密钥管理 | `POST /api/v1/ota-keys`（9.3.1）、`GET /api/v1/ota-keys`（9.3.2，分页）、`PUT /api/v1/ota-keys/{id}/activate`（9.3.3）、`PUT /api/v1/ota-keys/{id}/revoke`（9.3.4，`confirm_text=REVOKE`）| 上传/激活/吊销限超管，查询只读人员及以上 |
| 版本矩阵 | `GET /api/v1/versions/matrix`（9.4.4，双基准落后标注，见十二）| 只读人员及以上 |
| 任务管理 | `POST /api/v1/ota/tasks`（9.5.1）、`GET /api/v1/ota/tasks`（9.5.2，分页+时间范围）、`GET /api/v1/ota/tasks/{id}`（9.5.3）、`.../retry`、`.../cancel`、`.../rollback`、`.../retry-failed`（9.5.4-9.5.7）、`.../resume`、`.../abort`（9.5.8-9.5.9）| 运维人员及以上；跨租户 `by_tenant_model` 需超管 |
| 进度查询 | `GET /api/v1/ota/tasks/{id}/progress`（9.6.1，轮询兜底）| 只读人员及以上 |
| 配置校验 | `POST /api/v1/system/config/validate`（9.9）| 超管 / ServiceAccount |
| 高风险任务只读查询（内部）| `GET /internal/ota/devices/{deviceId}/active-high-risk-task` | 供设备基座测试标记取消流程回调，见第七章 |

**分页规范统一**：`page`（默认 1）/`page_size`（默认 20，最大 100），响应含 `total_count`/`total_pages`；越界页码返回空数组而非 404。

---

## 十二、版本矩阵双基准落后判定（对齐 4.3）

`version_lag_level_cluster`（设备群内基准：与同机型 `current_version` 最大值对比）与 `version_lag_level_repo`（仓库基准：与固件仓库该机型 `risk_level=normal` 的最高版本对比）**两个独立维度**，取较严重者展示；`warn`：小版本差异 >2（大版本相同）；`critical`：大版本差异 ≥1 或小版本差异 >5；修订号差异不触发。**"全员落后"场景**（群内一致但仓库有更新版本）须单独触发仓库基准标注，避免被误判为"群内一致=无需升级"。快捷发起升级时异步触发一次不落库的兼容性预检（预检并发上限 10、单次目标数上限 500、超时 5 秒）。

---

## 十三、日志与审计（对齐 4.8）

| 日志类型 | 保留期 | 归档 |
| --- | --- | --- |
| 操作日志 | 在线 180 天 | 归档至 IA，自写入起满 1 年物理删除 |
| 升级状态日志（15 态全链路）| 在线 90 天 | 同上 |
| 本地缓存补传日志（标注补传时间戳与原始时间戳）| 在线 90 天 | 同上 |

在线检索 SLA 3 秒（超时 10 秒展示重试兜底）；归档检索 SLA 5 分钟内；支持按 SN/设备类型/操作类型/时间范围/任务状态/错误码筛选；日志不可篡改；存储使用率超 80% 告警。

---

## 十四、弱网可靠性（对齐 4.7）

- 状态先写本地持久化缓存（≥7 天）再上报，网络恢复后经 `ota/status-report` 批量补传（单批 ≤100 条），设备端维护补传游标（记录已成功提交的最大 `original_reported_at`），`duplicate`（幂等跳过，不重试）/`invalid_timestamp`（可重试）/`task_not_found`（丢弃写本地日志，不重试）。
- HTTP Range 断点续传是 MVP 必须能力；OSS 预签名 URL 默认 24 小时有效期，剩余 <1 小时或收到 `ERR_URL_EXPIRED` 时自动刷新，旧 URL 保留 5 分钟宽限期（新旧同时有效）。
- `progress-push` 推送失败走指数退避重试（1s→2s→4s，上限 8s，最多 3 次），全部失败后转入离线补传路径。

---

## 十五、迁移落地计划

| 步骤 | 内容 | 依赖 |
| --- | --- | --- |
| 1 | 建 `realman-ota-contract`：固件/密钥/任务 DTO、15 态枚举、30 个错误码常量、Feign 契约 | **已完成** |
| 2 | 建 `realman-ota-biz`：数据模型（3.2/4.1/8 章）+ 前置校验（六）+ 状态机（八）| **已完成**，依赖设备基座（读设备信息/is_test_device）、通信中台（`ota/resource-probe` 新增 Topic、下行发布）|
| 3 | 补齐设备基座频率限制（`ERR_REGISTER_RATE_LIMIT`/`ERR_SECRET_GENERATE_RATE_LIMIT`）+ 高风险任务查询回调对接 | **已完成**，见第七章 |
| 4 | 建 `realman-ota-api`：管理端 REST（十一章）+ 系统设置校验接口 | **已完成**，依赖步骤 2 |
| 5 | 通信中台侧补齐 `ota/token-refresh` 完整闭环 | **已完成**：`MqttMessageDispatcher#handleTokenRefresh` 归一化 DeviceUplinkEvent 之外，实际调用新增的 `DeviceMgmtFeignClient#refreshToken`（内部端点 `POST /internal/device/refresh-token`，复用 `DeviceAdminServiceImpl#refreshToken`）完成续签，再通过 `MqttPublisher` 把新 Token 下行回传到同一 `ota/token-refresh` Topic |
| 6 | 迁移现状 `OtaController`/`IotOtaServiceImpl`/`OtaProgressHandler` 数据到新状态机，旧 8 态到新 15 态的存量任务数据迁移脚本 | 依赖步骤 2-4，**尚未开始** |

---

## 十六、验收标准

沿用 PRD 附录"十、验收标准"48 项功能验收 + 非功能验收（在线日志检索 ≤3 秒、200 台并发轮询 P99 ≤500ms、Ed25519 单次验签 ≤50ms 等），本文档不重复列出，实施时直接对照 PRD 原文逐项验收，验收项编号与本文档章节的对应关系：设备状态/资源检查 → 六章验收项 #7-8；版本双重校验 → 六.3 验收项 #9；签名相关 → 四章验收项 #31-39；批量策略 → 五章验收项 #21-26；Token/注册 → 七章验收项 #45-48。
