# IoT 设备管理服务 - 详细需求文档

## 1. 文档说明

本文档基于产品需求（主控端管理、授权管理、设备管理/机器人管理）与当前 IoT 服务代码实现整理，用于对齐业务含义、数据模型与接口能力，并标注已实现与待完善项。  
时间格式统一为：**Y/M/D H:M:S**（对应实现中的 `LocalDateTime` / `yyyy-MM-dd HH:mm:ss`）。

---

## 2. 术语与数据归属

| 术语 | 说明 |
|------|------|
| **主控设备** | 主控端，对应 `iot_device.device_type = 2`，平台通过 MQTT 与其通信，下发操作指令等。 |
| **机器人设备** | 机器人端，对应 `iot_device.device_type = 1`，支持上线/下线、参数设置、固件升级等。 |
| **设备** | 泛指 `iot_device` 表中的一条记录，通过 `device_type` 区分主控/机器人。 |
| **授权** | 管理员将主控设备与机器人设备授权给指定租户或企业（或其管理员），并设置生效/失效时间及启用状态。 |
| **操作员** | 登录主控设备的用户；主控端管理需记录其登录信息及当时关联的机器人。 |

**数据归属**：  
- 设备与机器人的**基本信息**均存放在 **`iot_device`** 表中，通过 **`device_type`** 区分：**1-机器人设备，2-主控设备**。  
- **授权关系**存放在 **`iot_device_auth`**（设备授权表），由平台管理员或租户管理员维护。

---

## 3. 一级模块一：授权管理

### 3.1 业务描述

管理员（平台管理员或租户管理员）将**主控设备**与**机器人设备**授权给指定租户或企业（或其管理员），并可设置授权状态、生效时间与失效时间。  
配套关系约束：一个账号对应一台主控、一台机器人；操作员不能自行切换机器人，仅睿尔曼超级管理员可在后台更换账号与机器的关系；支持一个账号在不同时间段绑定不同机器，但操作员同一时刻只能操作一台机器人。

### 3.2 授权表（iot_device_auth）字段

| 字段 | 类型 | 说明 |
|------|------|------|
| 授权ID | id | 主键 |
| 所属租户 | subject_type + subject_id | 代理商/企业：subject_type 可为 TENANT 等，subject_id 为租户/企业 ID 或名称 |
| 修改时间 | update_time | 最后修改时间，格式 Y/M/D H:M:S |
| 主控端ID | controller_id | 主控设备标识（可与 iot_device.id 或 device_code 对应） |
| 机器人ID | device_id / device_code | 机器人设备标识（iot_device 表） |
| 配套信息 | - | 见 3.1 业务约束（一个账号一台主控一台机器人等） |
| 遥操平台账号 | - | 用户ID、秘钥（若需单独表可扩展） |
| 管理后台账号 | admin_user_id / admin_username | 与 sys_user 关联 |
| 生效时间 | effective_time | 格式 Y/M/D H:M:S |
| 失效时间 | expire_time | 格式 Y/M/D H:M:S |
| 授权状态 | status | 1-启用，0-禁用 |

**当前实现**：  
- 实体 `IotDeviceAuth` 已包含：subject_type、subject_id、controller_id、device_id、device_code、admin_user_id、admin_username、effective_time、expire_time、status、create_time、update_time 等。  
- 列表查询条件见 3.4。

### 3.3 条件查询

- 按所属公司名称（可映射为 subject_id / 租户名）
- 按主控端/ID：controller_id
- 按机器人/ID：device_id 或 device_code
- 按用户/ID：admin_user_id 或 subject_id（用户维度）
- 按时间：生效时间、失效时间范围（startEffectiveTime、endEffectiveTime），格式 Y/M/D H:M:S

**当前实现**：`DeviceAuthQueryDTO` 支持 subjectType、subjectId、controllerId、deviceId、startEffectiveTime、endEffectiveTime、status；`IIotDeviceAuthService.queryAuthPage` 支持分页与数据权限（非超管仅看本人 USER 主体）。

### 3.4 管理操作

- **复选**：多选授权记录（前端实现）
- **导出**：导出为 .excel（待实现或由通用导出能力承接）
- **新增**：POST /api/device/auth，Body 为 IotDeviceAuth
- **编辑**：PUT /api/device/auth/{id}
- **删除**：DELETE /api/device/auth/{id}

**当前实现**：DeviceAuthController 已提供分页、新增、编辑、删除接口。

---

## 4. 一级模块二：主控端管理

### 4.1 业务描述

主控端管理面向 **device_type=2** 的主控设备，提供新增、编辑、删除及导出；当操作员登录主控设备时，需记录登录信息及当时关联的机器人设备信息；平台通过 MQTT 与主控设备通信，包括下发操作指令等。

### 4.2 主控设备表（iot_device，device_type=2）字段

| 字段 | 说明 |
|------|------|
| 主控端ID | id / device_code（设备唯一标识） |
| 创建时间 | create_time，格式 Y/M/D H:M:S |
| 修改时间 | update_time，格式 Y/M/D H:M:S |
| 型号 | device_model |
| 版本 | firmware_version |
| 授权 | 通过 iot_device_auth 关联，含生效时间、失效时间 |
| 状态 | status：1-在线(运行)、2-离线 |
| 关联的操作员 | 通过授权表或登录记录关联（见 4.5） |
| 关联的机器人 | 通过 iot_device_auth.device_id 关联 |
| 位置 | 国家/城市/区/街道/楼宇（可存 description 或扩展字段）、longitude、latitude |
| 最后一次登录时间 | 主控端登录时间（需扩展字段或登录记录表，见 4.5） |

**当前实现**：  
- `iot_device` 已含：id、device_code、device_name、device_type、device_model、firmware_version、status、last_online_time、last_offline_time、longitude、latitude、create_time、update_time 等。  
- 上下线时已更新 **last_online_time**、**last_offline_time**（DeviceOnlineOfflineHandler）。  
- **最后一次登录时间**及**登录时关联的机器人**需通过扩展字段或主控登录记录表实现（见 4.5）。

### 4.3 条件查询

- **按状态**：status，运行(1)/离线(2)
- **按关联**：关联的操作员、关联的机器人（通过 iot_device_auth 关联查询）
- **按时间**：开始时间、结束时间（如 create_time 或 last_online_time 范围），格式 Y/M/D H:M:S

**当前实现**：`DeviceRequestDTO` 支持 deviceType、status、startTime、endTime；设备列表 Mapper 在非超管下按 iot_device_auth 做数据权限过滤（授权有效且主体匹配）。

### 4.4 管理操作

- **复选**：多选主控设备（前端）
- **导出**：导出为 .excel（待实现或通用导出）
- **新增**：POST /api/device/add，Body 中 deviceType=2
- **编辑**：需提供 PUT /api/device/{id} 或类似更新接口（当前有 detail 与 detailAgg，更新可复用现有设备更新逻辑）
- **删除**：逻辑删除（del_flag）或物理删除（需按规范补充接口）

**当前实现**：DeviceController 提供 add、list（分页+条件）、detail、detailAgg；禁用/启用 PUT /api/device/{deviceId}/status/{status}。

### 4.5 操作员登录主控设备时记录

- **需求**：当操作员登录主控设备时，记录登录信息及当时关联的机器人设备信息。
- **建议**：  
  - 在主控设备侧登录成功后，通过 MQTT 或 HTTP 上报“登录事件”到平台（如 topic 或接口），携带：主控 device_code、操作员标识、当前关联的机器人 device_id/device_code、登录时间。  
  - 平台落库：可新增**主控登录记录表**（如 iot_controller_login_log），字段含：主控端ID、操作员ID/账号、关联机器人ID、登录时间等；或复用/扩展操作日志表。  
- **当前实现**：设备操作日志表 `iot_device_operation_log` 存在，可扩展类型为“主控登录”；若需独立表需在库表与代码中新增。

### 4.6 操作记录（主控遥操操作记录）

- **需求**：记录当前遥操员使用该主控设备操控哪台机器人完成工单的时间。
- **规则**：
  - **开始操作时间** = 工单开启时间（操作员点击「开始工单」时的 `actual_start_time`）。
  - **结束操作时间**：正常提交的工单 = 提交时间（`submit_time`）；异常工单（超时/关闭）= 工单失效时间（`plan_end_time`）。
- **实现**：
  - 表 `controller_operation_record`：主控ID、主控编号、机器人ID、机器人编号、遥操员、工单ID、开始操作时间、结束操作时间。
  - 工单**开启**时：按工单绑定设备（主控 + 机器人）为该工单创建一条或多条操作记录，`start_time` = 工单开启时间，`end_time` 为空。
  - 工单**提交**时：将该工单下所有未结束的操作记录 `end_time` 置为提交时间。
  - 工单**超时**（定时任务标记为 TIMEOUT）或**关闭**时：将该工单下未结束的操作记录 `end_time` 置为工单失效时间（`plan_end_time`）。
- **接口**：见 7.2 操作记录分页、操作记录导出。

### 4.7 使用状态（主控使用状态）

- **需求**：主控端「使用状态」页展示：
  - **最近登录时间**：当前主控最后一次被登录时间（`iot_device.last_login_time`，由主控端登录记录接口更新）。
  - **最近一次遥操开始时间**：该主控在 `controller_operation_record` 中最近一条记录的 `start_time`。
  - **当前设备**：当前正在遥操的机器人状态（即该主控下 `end_time` 为空的操作记录对应的机器人），含机器人ID、编号、名称、状态、型号、版本等。
  - **可使用的机器人**：与该主控绑定的机器人列表（来自 `iot_device_auth` 中 `controller_id` = 该主控且启用的 `device_id`，对应 `iot_device` 中 `device_type=1` 的设备）。
- **接口**：GET `/api/master/usage-status/{controllerCode}`，返回 `UsageStatusVO`（见 7.2）。

### 4.8 平台与主控设备接口与 MQTT 通信

- 平台通过 MQTT 与主控设备通信，包括下发操作指令等。
- **当前实现**：  
  - 主控设备作为设备的一种（device_type=2），使用同一套 MQTT 鉴权与 Topic 规则。  
  - 设备连接层：EMQX HTTP Auth/ACL 回调（MqttAuthController），设备端 clientId/username 为 deviceCode，password 为 deviceSecret（MD5(deviceCode)）。  
  - 下行：可向 `device/{deviceCode}/config/push`、`device/{deviceCode}/command/restart`、`device/{deviceCode}/command/emergency-stop`、`device/{deviceCode}/ota/notify` 等下发配置、重启、紧急停机、OTA 通知；主控专用操作指令可扩展 topic，如 `device/{deviceCode}/command/xxx`。  
  - 上行：设备上报 status/report、config/ack、`command/{cmd}/ack`（如 restart/emergency-stop/poweroff/reset）、ota/progress、log/operation 等，由 MqttMessageDispatcher 分发到各 Handler（指令 ACK 使用通用 Handler 处理多 cmd）。

---

## 5. 一级模块三：设备管理（机器人设备）

### 5.1 业务描述

设备管理面向 **device_type=1** 的机器人设备，实现新增、编辑、删除及导出；并通过 MQTT 与机器人设备通信，包括机器人上线、下线、参数设置、固件升级等。

### 5.2 机器人设备表（iot_device，device_type=1）

与主控设备共用 `iot_device` 表，通过 **device_type=1** 区分。字段与 4.2 一致（型号、版本、状态、位置、last_online_time、last_offline_time 等）；“关联的机器人”在授权表中体现为被授权设备。

### 5.3 条件查询

与 4.3 类似，按状态、按关联（操作员/机器人）、按时间；列表查询时传入 **deviceType=1** 即可只查机器人设备。

**当前实现**：DeviceController POST /api/device/list 使用 DeviceRequestDTO，支持 deviceType、status、startTime、endTime 等；数据权限通过 IotDeviceMapper.xml 关联 iot_device_auth 实现。

### 5.4 管理操作

- **新增**：POST /api/device/add，Body 中 deviceType=1  
- **编辑**：更新设备基础信息（需确认是否有 PUT /api/device/{id} 或等效接口）  
- **删除**：逻辑删除或物理删除（需按规范提供）  
- **导出**：.excel 导出（待实现或通用导出）

**当前实现**：新增、分页列表、详情、聚合详情（含设备参数配置 deviceConfigs）、禁用/启用均已支持。

### 5.5 与机器人设备的 MQTT 通信

- **上线/下线**：  
  - EMQX $SYS 主题：设备连接/断开时，EMQX 发布 connected/disconnected 事件，平台 DeviceOnlineOfflineHandler 处理，更新 iot_device 的 status、last_online_time、last_offline_time，并维护 Redis 在线集合与 WebSocket 推送。  
- **参数设置**：  
  - 平台调用 POST /api/device/{deviceId}/config/sync，Body 为 key-value 参数；在线则通过 MQTT 推送至 device/{deviceCode}/config/push（AES 加密），设备回复 config/ack 后更新 iot_device_config 同步状态。  
- **固件升级**：  
  - 固件上传、任务创建、执行升级任务（OTA 模块）：平台向 device/{deviceCode}/ota/notify 下发通知，设备上报 ota/progress，平台更新 iot_ota_upgrade_record 与设备 firmware_version。  
- **远程重启**：  
  - POST /api/device/{deviceId}/restart，平台向 device/{deviceCode}/command/restart 下发加密指令，设备回复 command/restart/ack。
- **紧急停机**：  
  - POST /api/device/{deviceId}/emergency-stop，平台向 device/{deviceCode}/command/emergency-stop 下发加密指令，设备回复 command/emergency-stop/ack。
- **指令集 ACK（可扩展）**：  
  - 约定上行确认 Topic 为 `device/{deviceCode}/command/{cmd}/ack`，平台统一订阅 `device/+/command/+/ack` 并按 cmd 写入操作日志（已支持：restart、emergency-stop；可扩展：poweroff、reset 等）。

**当前实现**：上述 MQTT 上行/下行与 REST 接口均已实现，详见 README 与 DeviceController、OtaController、MqttMessageDispatcher。

---

## 6. 一级模块四：工单管理

### 6.1 业务描述

工单管理包含**工单合规配置**与**工单管理**两个子模块，归属 realman-boot-iot，不单独建模块。

- **工单合规配置**：平台管理员定义工单执行规则（任务类型、时限策略、验收要求、超时提醒与自动关闭等）；工单创建时必须绑定一条已启用的合规配置。
- **工单管理**：管理员创建工单并绑定主控设备与机器人；遥操人员通过主控端领取并执行工单（开始、填写补充信息、提交）；管理员负责审核与关闭。工单开启/提交/超时/关闭时会同步更新**主控遥操操作记录**（`controller_operation_record`），见 4.6。

**角色**：平台管理员（维护合规配置、创建/审核/关闭工单）、遥操人员（主控端开始、补充信息、提交、填写超时原因）。

**与 IoT 关系**：工单绑定的主控对应 `iot_device.device_type=2`，机器人对应 `device_type=1`；授权关系复用 `iot_device_auth`；开启工单时关联 `iot_controller_login_log`，并写入 `controller_operation_record`。

### 6.2 工单合规配置

- **表**：`work_order_compliance_config`。字段含：规则ID、代理商/企业信息、任务场景、是否自动预警及预警时间（距任务结束前X，H:M:S）、是否有任务时限（默认启用）、工单是否需要验收、超时提交原因枚举及描述、超时未提交策略及超时时长（H:M:S）、应用状态（有工单绑定时为已应用）等。
- **条件查询**：按代理商/企业、应用状态筛选。
- **管理操作**：新增、编辑（仅未应用）、删除（仅未应用）、导出 Excel；当有工单绑定时自动置为“已应用”。
- **当前实现**：WorkOrderComplianceController，前缀 `/api/work-order/compliance`，提供分页、新增、编辑、删除、导出。

### 6.3 工单管理（业务要点）

- **工单主表**：`work_order`。含工单任务名称、代理商/部门、合规配置 ID、计划开始/结束时间、状态、开启信息（操作员、实际开始时间、登录记录 ID）、提交时间、超时原因、审核结果与审核人/时间、关闭人与关闭原因等。
- **绑定设备**：`work_order_device`。记录创建工单时绑定的主控（单台）与机器人（可多台），以及开启工单时的实际设备快照（含固件版本）。
- **附件与人员**：`work_order_attachment`（提交佐证图片）、`work_order_personnel`（参与人员，遥操员/监督员等）。
- **查询条件**：工单 ID/任务名称、代理商/企业、工单状态、主控/机器人、操作员、计划时间、实际开始/提交/审核时间、是否超时等。

### 6.4 工单状态流转

| 状态 | 说明 | 触发 |
|------|------|------|
| NOT_STARTED | 未开始 | 管理员创建完成 |
| IN_PROGRESS | 已开始 | 遥操人员点击「开始」，记录操作员与 actual_start_time |
| SUBMITTED | 已提交 | 遥操人员在 plan_end_time 前提交；若合规配置禁用验收则直接进入 COMPLETED |
| COMPLETED | 已完成 | 管理员审核（PASS/FAIL）或配置禁用验收时自动完成 |
| TIMEOUT | 超时异常 | 超过 plan_end_time 未提交，由定时任务或超时提交置为 TIMEOUT，需填写超时原因 |
| CLOSED | 已关闭 | 管理员关闭或合规配置启用时定时任务自动关闭 |

定时任务（WorkOrderSchedulerJob）：超时提醒（距结束前 X 分钟推送）、超时检测（IN_PROGRESS/NOT_STARTED 超 plan_end_time 置为 TIMEOUT）、超时自动关闭（TIMEOUT 且启用自动关闭时置为 CLOSED）。

### 6.5 与主控/设备及操作记录的关系

- 创建工单时绑定主控设备（iot_device.id/device_code，device_type=2）与机器人（device_type=1），需在授权范围内。
- 遥操人员登录主控后，通过 GET 主控端待执行工单列表（按主控 deviceCode、状态=未开始、时间窗口筛选）领取工单；点击「开始」时写入 `controller_operation_record`（start_time=工单开启时间），提交/超时/关闭时更新该工单下操作记录的 end_time（见 4.6）。
- 主控使用状态（4.7）中的「最近一次遥操开始时间」「当前设备」依赖 `controller_operation_record`，与工单执行一一对应。

**当前实现**：工单创建、分页、详情、开始、提交、超时原因、审核、关闭、主控端待开始列表、工单附件上传；合规配置分页、增删改、导出；定时任务已实现超时检测与自动关闭。

---

## 7. 数据模型与表结构汇总

### 7.1 核心表

| 表名 | 说明 |
|------|------|
| iot_device | 设备与机器人基本信息；device_type：1-机器人，2-主控；含 last_online_time、last_offline_time、经纬度等 |
| iot_device_auth | 设备授权关系：主体(租户/用户)、主控端、机器人、生效/失效时间、授权状态 |
| iot_device_config | 设备参数配置（key-value），含同步状态与同步时间 |
| iot_device_status | 设备状态上报历史（温湿度、电量、定位、运行状态等） |
| iot_device_operation_log | 设备操作日志（平台/设备来源、操作类型、结果、操作时间等） |
| iot_ota_firmware | OTA 固件包 |
| iot_ota_upgrade_task | OTA 升级任务 |
| iot_ota_upgrade_record | 每台设备的升级记录与进度 |
| controller_operation_record | 主控遥操操作记录（主控/机器人/遥操员/工单、开始与结束操作时间） |
| work_order_compliance_config | 工单合规配置（任务类型、时限与验收规则、超时提醒与自动关闭等） |
| work_order | 工单主表（任务名称、合规配置、计划时间、状态、开启/提交/审核/关闭信息） |
| work_order_device | 工单绑定设备（计划主控/机器人及开启时实际设备快照） |
| work_order_attachment | 工单附件（佐证图片等） |
| work_order_personnel | 工单参与人员（遥操员/监督员等） |

### 7.2 时间字段格式

所有时间字段在接口与存储中统一为 **Y/M/D H:M:S**（实现为 `LocalDateTime`，序列化格式一般为 `yyyy-MM-dd HH:mm:ss`），包括：  
创建时间、修改时间、生效时间、失效时间、最后一次登录时间、开始/结束时间（条件查询）、last_online_time、last_offline_time 等。

---

## 8. 接口清单（与现有实现对照）

### 8.1 设备管理（机器人设备：device_type=1）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/device/add | 新增机器人设备 | 已实现 |
| POST | /api/device/list | 分页+条件查询机器人设备列表 | 已实现 |
| GET | /api/device/{id} | 机器人设备基础详情 | 已实现 |
| GET | /api/device/{id}/detail | 机器人设备聚合详情（含实时状态、设备参数配置、最近日志） | 已实现 |
| PUT | /api/device/{deviceId} | 编辑机器人设备 | 已实现 |
| DELETE | /api/device/{deviceId} | 删除机器人设备（逻辑删除） | 已实现 |
| POST | /api/device/export | 导出机器人设备列表 Excel | 已实现 |
| POST | /api/device/{id}/config/sync | 参数设置并同步（在线推送/离线待同步） | 已实现 |
| GET | /api/device/{id}/monitor | 实时监控状态 | 已实现 |
| POST | /api/device/{id}/restart | 远程重启 | 已实现 |
| POST | /api/device/{id}/emergency-stop | 紧急停机 | 已实现 |
| PUT | /api/device/{id}/status/{status} | 禁用/启用 | 已实现 |
| POST | /api/device/batch/online-status | 批量在线状态 | 已实现 |

### 8.2 主控端管理（主控设备：device_type=2）

**说明**：主控端管理接口统一前缀为 **`/api/teleop`**（对应 `MasterDeviceController` 的 `@RequestMapping("/api/teleop")` 与一系列 `IMaster*` 服务接口）。

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/teleop/add | 新增主控设备 | 已实现 |
| POST | /api/teleop/list | 分页+条件查询主控设备列表 | 已实现 |
| GET  | /api/teleop/{id} | 主控设备基础详情 | 已实现 |
| GET  | /api/teleop/{id}/detail | 主控设备聚合详情 | 已实现 |
| PUT  | /api/teleop/{id} | 编辑主控设备 | 已实现 |
| DELETE | /api/teleop/{id} | 删除主控设备（逻辑删除） | 已实现 |
| POST | /api/teleop/login | 主控端登录记录 | 已实现 |
| POST | /api/teleop/export | 导出主控设备列表 Excel | 已实现 |
| POST | /api/teleop/{id}/config/sync | 参数设置并同步 | 已实现 |
| POST | /api/teleop/{id}/control-params | 设置主控端力反馈及运动与安全参数 | **已实现** |
| GET  | /api/teleop/{id}/monitor | 实时监控状态 | 已实现 |
| POST | /api/teleop/{id}/restart | 远程重启 | 已实现 |
| POST | /api/teleop/{id}/emergency-stop | 紧急停机 | 已实现 |
| PUT  | /api/teleop/{id}/status/{status} | 禁用/启用 | 已实现 |
| POST | /api/teleop/batch/online-status | 批量在线状态 | 已实现 |
| POST | /api/teleop/operation-record/page | 操作记录分页 | 已实现 |
| POST | /api/teleop/operation-record/export | 操作记录导出 Excel | 已实现 |
| GET  | /api/teleop/usage-status/{controllerCode} | 主控使用状态（最近登录、最近遥操开始时间、当前设备、可使用的机器人） | 已实现 |

### 8.3 授权管理

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/device/auth/page | 分页查询授权列表（含条件） | 已实现 |
| POST | /api/device/auth | 新增授权 | 已实现 |
| PUT | /api/device/auth/{id} | 编辑授权 | 已实现 |
| DELETE | /api/device/auth/{id} | 删除授权（逻辑删除） | 已实现 |
| POST | /api/device/auth/export | 导出授权列表 Excel | 已实现 |

### 8.4 OTA（固件升级）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/ota/firmware/upload/chunk | 固件分片上传 | 已实现 |
| GET | /api/ota/firmware/upload/chunks | 查询已上传分片 | 已实现 |
| POST | /api/ota/firmware/upload/merge | 合并发布固件 | 已实现 |
| POST | /api/ota/task/create | 创建升级任务 | 已实现 |
| POST | /api/ota/task/{id}/execute | 执行升级任务 | 已实现 |

### 8.5 内部（EMQX 回调）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /internal/mqtt/auth | EMQX 认证回调 | 已实现 |
| POST | /internal/mqtt/acl | EMQX ACL 回调 | 已实现 |

### 8.6 工单合规配置

**说明**：前缀 **`/api/work-order/compliance`**，角色为平台管理员。

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/work-order/compliance/page | 分页查询合规配置（Body: WorkOrderComplianceQueryDTO） | 已实现 |
| POST | /api/work-order/compliance | 新增合规配置 | 已实现 |
| PUT | /api/work-order/compliance/{id} | 编辑合规配置（仅未启用） | 已实现 |
| DELETE | /api/work-order/compliance/{id} | 删除合规配置（逻辑删除，仅未启用） | 已实现 |
| POST | /api/work-order/compliance/export | 导出合规配置 Excel | 已实现 |

### 8.7 工单管理

**说明**：前缀 **`/api/work-order`**。管理端：分页、创建、详情、审核、关闭、导出；主控端/遥操：待开始列表、开始、提交、超时原因、附件上传。

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/work-order/page | 分页查询工单（Body: WorkOrderQueryDTO） | 已实现 |
| POST | /api/work-order | 创建工单（Body: WorkOrderCreateDTO，含绑定设备） | 已实现 |
| GET | /api/work-order/{id} | 工单详情 | 已实现 |
| POST | /api/work-order/{id}/start | 开启工单（Body: WorkOrderStartDTO，记录操作员与 actual_start_time） | 已实现 |
| POST | /api/work-order/{id}/submit | 提交工单 | 已实现 |
| POST | /api/work-order/{id}/timeout-reason | 填写超时原因（Body: WorkOrderTimeoutReasonDTO） | 已实现 |
| POST | /api/work-order/{id}/audit | 审核工单（Body: WorkOrderAuditDTO，PASS/FAIL） | 已实现 |
| POST | /api/work-order/{id}/close | 关闭工单（Body: 含 closeReason） | 已实现 |
| POST | /api/work-order/export | 导出工单列表 Excel | 已实现 |
| GET | /api/work-order/pending/controller/{controllerCode} | 主控端待开始工单列表 | 已实现 |
| POST | /api/work-order/{id}/attachments | 新增工单附件（佐证图片等） | 已实现 |

---

## 9. 待完善与可选扩展

1. **主控端“最后一次登录时间”与“登录时关联的机器人”**  
   - **已实现**：表 `iot_controller_login_log`、实体 `IotMasterLoginLog`（Java 侧命名），`iot_device.last_login_time`；接口 POST `/api/teleop/login`（Body：MasterLoginDTO），由 `IMasterLoginResolveService` 记录登录并更新主控设备最后登录时间；删除均为逻辑删除。

2. **设备编辑接口**  
   - **已实现**：PUT `/api/device/{deviceId}`，Body 为 DeviceUpdateDTO（设备名、型号、序列号、描述、经纬度等）。

3. **设备删除**  
   - **已实现**：DELETE `/api/device/{deviceId}`，逻辑删除（del_flag）；授权删除亦为逻辑删除（DELETE `/api/device/auth/{id}`）。

4. **导出 .excel**  
   - **已实现**：POST `/api/device/export`（机器人设备，Body 同 list 条件）、POST `/api/controller/export`（主控设备，Body 同 list 条件）、POST `/api/device/auth/export`（Body 同授权分页条件），返回 xlsx 文件，受数据权限控制。

5. **位置与地址**  
   - 国家/城市/区/街道/楼宇若需独立字段，可在 iot_device 表扩展或使用 JSON 字段/扩展表。

6. **iot_device_auth 表**  
   - **已实现**：init.sql 中已包含 `iot_device_auth`、`iot_controller_login_log` 建表及 `iot_device.last_login_time` 字段。

7. **操作记录与使用状态**  
   - **已实现**：表 `controller_operation_record`，对应实体 `MasterOperationRecord` 与服务接口 `IMasterOperationRecordService`；工单开启/提交/超时/关闭时自动写入或更新操作记录；POST `/api/teleop/operation-record/page` 分页、POST `/api/teleop/operation-record/export` 导出；GET `/api/teleop/usage-status/{controllerCode}` 通过 `IMasterUsageStatusService` 返回 `UsageStatusVO`（lastLoginTime、lastRemoteOperationStartTime、currentDevice、availableRobots）。

8. **工单管理**  
   - **已实现**：工单合规配置与工单管理全流程见 **第 6 章** 与 **8.6、8.7 接口清单**；表 `work_order_compliance_config`、`work_order`、`work_order_device`、`work_order_attachment`、`work_order_personnel`；定时任务超时检测与自动关闭；主控端待开始工单列表、开始/提交/审核/关闭、附件上传。

---

## 10. 参考

- 模块结构、鉴权架构、MQTT Topic、API 列表：见 `realman-boot-iot/README.md`  
- 设备详情聚合与设备参数配置返回：`IotDeviceServiceImpl.getDeviceDetail`、`DeviceDetailVO.deviceConfigs`  
- 上下线更新 last_online_time / last_offline_time：`DeviceOnlineOfflineHandler`  
- 授权查询与数据权限：`IotDeviceMapper.xml`、`DeviceAuthQueryDTO`、`IIotDeviceAuthService.queryAuthPage`
- 工单业务与设计：`docs/工单管理设计文档.md`；工单服务 `WorkOrderServiceImpl`、合规配置 `WorkOrderComplianceConfigServiceImpl`、定时任务 `WorkOrderSchedulerJob`
