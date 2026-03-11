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

### 4.6 平台与主控设备 MQTT 通信

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

## 6. 数据模型与表结构汇总

### 6.1 核心表

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

### 6.2 时间字段格式

所有时间字段在接口与存储中统一为 **Y/M/D H:M:S**（实现为 `LocalDateTime`，序列化格式一般为 `yyyy-MM-dd HH:mm:ss`），包括：  
创建时间、修改时间、生效时间、失效时间、最后一次登录时间、开始/结束时间（条件查询）、last_online_time、last_offline_time 等。

---

## 7. 接口清单（与现有实现对照）

### 7.1 设备管理（机器人设备：device_type=1）

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

### 7.2 主控端管理（主控设备：device_type=2）

| 方法 | 路径                                   | 说明 | 状态 |
|------|--------------------------------------|------|------|
| POST | /api/controller/add                      | 新增主控设备 | 已实现 |
| POST | /api/controller/list                 | 分页+条件查询主控设备列表 | 已实现 |
| GET | /api/controller/{id}                 | 主控设备基础详情 | 已实现 |
| GET | /api/controller/{id}/detail          | 主控设备聚合详情 | 已实现 |
| PUT | /api/controller/{id}                 | 编辑主控设备 | 已实现 |
| DELETE | /api/controller/{id}                 | 删除主控设备（逻辑删除） | 已实现 |
| POST | /api/controller/login                | 主控端登录记录 | 已实现 |
| POST | /api/controller/export               | 导出主控设备列表 Excel | 已实现 |
| POST | /api/controller/{id}/config/sync     | 参数设置并同步 | 已实现 |
| GET | /api/controller/{id}/monitor         | 实时监控状态 | 已实现 |
| POST | /api/controller/{id}/restart         | 远程重启 | 已实现 |
| POST | /api/controller/{id}/emergency-stop  | 紧急停机 | 已实现 |
| PUT | /api/controller/{id}/status/{status} | 禁用/启用 | 已实现 |
| POST | /api/controller/batch/online-status  | 批量在线状态 | 已实现 |

### 7.3 授权管理

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/device/auth/page | 分页查询授权列表（含条件） | 已实现 |
| POST | /api/device/auth | 新增授权 | 已实现 |
| PUT | /api/device/auth/{id} | 编辑授权 | 已实现 |
| DELETE | /api/device/auth/{id} | 删除授权（逻辑删除） | 已实现 |
| POST | /api/device/auth/export | 导出授权列表 Excel | 已实现 |

### 7.4 OTA（固件升级）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /api/ota/firmware/upload/chunk | 固件分片上传 | 已实现 |
| GET | /api/ota/firmware/upload/chunks | 查询已上传分片 | 已实现 |
| POST | /api/ota/firmware/upload/merge | 合并发布固件 | 已实现 |
| POST | /api/ota/task/create | 创建升级任务 | 已实现 |
| POST | /api/ota/task/{id}/execute | 执行升级任务 | 已实现 |

### 7.5 内部（EMQX 回调）

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | /internal/mqtt/auth | EMQX 认证回调 | 已实现 |
| POST | /internal/mqtt/acl | EMQX ACL 回调 | 已实现 |

---

## 8. 待完善与可选扩展

1. **主控端“最后一次登录时间”与“登录时关联的机器人”**  
   - **已实现**：表 `iot_controller_login_log`、实体 `IotControllerLoginLog`、`iot_device.last_login_time`；接口 POST `/api/controller/login`（Body：ControllerLoginDTO），记录登录并更新主控设备最后登录时间；删除均为逻辑删除。

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

---

## 9. 参考

- 模块结构、鉴权架构、MQTT Topic、API 列表：见 `realman-boot-iot/README.md`  
- 设备详情聚合与设备参数配置返回：`IotDeviceServiceImpl.getDeviceDetail`、`DeviceDetailVO.deviceConfigs`  
- 上下线更新 last_online_time / last_offline_time：`DeviceOnlineOfflineHandler`  
- 授权查询与数据权限：`IotDeviceMapper.xml`、`DeviceAuthQueryDTO`、`IIotDeviceAuthService.queryAuthPage`
