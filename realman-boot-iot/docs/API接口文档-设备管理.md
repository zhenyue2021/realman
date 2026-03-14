# realman-iot 接口文档（前后端联调）

本文档用于前后端联调，描述 realman-iot 项目对外提供的 HTTP 接口、请求/响应格式及鉴权说明。

---

## 1. 基础说明

### 1.1 服务信息

| 项 | 说明 |
|----|------|
| 服务名 | realman-iot |
| 默认端口 | 8085 |
| 上下文路径 | `/realman-iot` |
| 接口根地址 | `http://{host}:8085/realman-iot` |

### 1.2 统一响应格式

除「导出 Excel」等返回二进制流的接口外，其余接口均返回 JSON，格式为 `ApiResult<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 200 成功，500 失败 |
| message | string | 提示信息 |
| data | object/array/null | 业务数据，失败时常为 null |

### 1.3 鉴权与请求头

- **登录态**：需在请求头中携带平台 JWT Token（如 `Authorization: Bearer <token>`），部分列表/导出接口会根据 `JwtUtil.getUserNameByToken(request)` 做数据权限（当前用户、租户、是否超管）。
- **租户**：若有多租户，可传请求头 `tenant-id`。
- **内部接口**：`/internal/mqtt/*` 仅供 EMQX 回调使用，联调时一般不需调用。

---

## 2. 机器人设备管理（/api/device）

**Base Path**: `POST/GET/PUT/DELETE /realman-iot/api/device`  
**说明**：device_type=1，机器人设备的增删改查、参数同步、监控、重启、紧急停机、导出等。

### 2.1 新增机器人设备

- **接口**: `POST /api/device/add`
- **请求体**: `DeviceAddDTO`（JSON）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deviceCode | string | 是 | 设备编号 |
| deviceName | string | 是 | 设备名称 |
| productId | string | 否 | 产品ID |
| deviceModel | string | 否 | 型号 |
| serialNumber | string | 否 | 序列号 |
| macAddress | string | 否 | 网卡MAC |
| description | string | 否 | 描述 |

- **响应**: `ApiResult<IotDevice>`，data 为设备实体（含 id、deviceCode、deviceType=1 等）。

### 2.2 分页查询机器人设备列表

- **接口**: `POST /api/device/list`
- **请求体**: `DeviceRequestDTO`（JSON）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pageNo | int | 否 | 页码，默认 1 |
| pageSize | int | 否 | 每页条数，默认 10 |
| deviceName | string | 否 | 设备名称（模糊） |
| status | int | 否 | 状态：0未激活 1在线 2离线 3禁用 |
| productId | string | 否 | 产品ID |
| startTime | string | 否 | 创建时间起，yyyy-MM-dd HH:mm:ss |
| endTime | string | 否 | 创建时间止 |

- **响应**: `ApiResult<IPage<IotDevice>>`，data 为分页结果（records、total、size、current 等）。  
- **说明**：会按当前登录用户、租户、是否超管做数据权限，无需传 currentUsername/superAdmin。

### 2.3 查询机器人设备详情

- **接口**: `GET /api/device/{deviceId}`
- **路径参数**: deviceId — 设备ID
- **响应**: `ApiResult<IotDevice>`

### 2.4 查询机器人设备详情（聚合）

- **接口**: `GET /api/device/{deviceId}/detail`
- **路径参数**: deviceId
- **响应**: `ApiResult<DeviceDetailVO>`

DeviceDetailVO 包含：device（基础信息）、online、lastHeartbeatTime、realtimeStatus、deviceConfigs、latestStatus、recentLogs。

### 2.5 设置并同步机器人设备参数

- **接口**: `POST /api/device/{deviceId}/config/sync`
- **路径参数**: deviceId
- **请求体**: `Map<String, Object>`，键值对形式的配置项
- **响应**: `ApiResult<Void>`，成功即“参数已保存，在线设备将立即收到加密配置推送”。

### 2.6 获取机器人设备实时监控状态

- **接口**: `GET /api/device/{deviceId}/monitor`
- **路径参数**: deviceId
- **响应**: `ApiResult<Map<String, Object>>`，内容为实时状态（优先 Redis，降级 DB）。

### 2.7 远程重启机器人设备

- **接口**: `POST /api/device/{deviceId}/restart`
- **路径参数**: deviceId
- **请求体**: `DeviceRestartDTO` — `{ "reason": "", "operator": "" }`
- **响应**: `ApiResult<Void>`

### 2.8 紧急停机机器人设备

- **接口**: `POST /api/device/{deviceId}/emergency-stop`
- **路径参数**: deviceId
- **请求体**: `EmergencyStopDTO` — `{ "reason": "", "operator": "" }`
- **响应**: `ApiResult<Void>`

### 2.9 编辑机器人设备

- **接口**: `PUT /api/device/{deviceId}`
- **路径参数**: deviceId
- **请求体**: `DeviceUpdateDTO` — deviceName、deviceModel、serialNumber、description、longitude、latitude（deviceCode/deviceType 不可改）
- **响应**: `ApiResult<Void>`

### 2.10 删除机器人设备（逻辑删除）

- **接口**: `DELETE /api/device/{deviceId}`
- **路径参数**: deviceId
- **响应**: `ApiResult<Void>`

### 2.11 禁用/启用机器人设备

- **接口**: `PUT /api/device/{deviceId}/status/{status}`
- **路径参数**: deviceId, status（如 0/1/3）
- **Query**: operator（可选，默认 "system"）
- **响应**: `ApiResult<Void>`

### 2.12 批量查询在线状态

- **接口**: `POST /api/device/batch/online-status`
- **请求体**: `List<String>` — 设备ID 列表
- **响应**: `ApiResult<List<Map<String, Object>>>`

### 2.13 导出机器人设备列表 Excel

- **接口**: `POST /api/device/export`
- **请求体**: 与 list 相同的 `DeviceRequestDTO`（条件与 list 一致）
- **响应**: 二进制流，Content-Type: application/octet-stream，带 attachment 文件名（robot_devices_*.xlsx）

---

## 3. 主控端管理（/api/teleop）

**Base Path**: `/realman-iot/api/teleop`  
**说明**：device_type=2，主控设备的注册、参数、监控、重启、紧急停机、登录解析、操作记录、导出、摄像头流等。

### 3.1 新增主控设备

- **接口**: `POST /api/teleop/add`
- **请求体**: 同 2.1 的 `DeviceAddDTO`（deviceType 由后端固定为 2）
- **响应**: `ApiResult<IotDevice>`

### 3.2 分页查询主控设备列表

- **接口**: `POST /api/teleop/list`
- **请求体**: 同 2.2 的 `DeviceRequestDTO`（deviceType 由后端固定为 2）
- **响应**: `ApiResult<IPage<IotDevice>>`

### 3.3 查询主控设备详情

- **接口**: `GET /api/teleop/{controllerId}`
- **响应**: `ApiResult<IotDevice>`

### 3.4 查询主控设备详情（聚合）

- **接口**: `GET /api/teleop/{controllerId}/detail`
- **响应**: `ApiResult<DeviceDetailVO>`

### 3.5 设置并同步主控设备参数

- **接口**: `POST /api/teleop/{controllerId}/config/sync`
- **请求体**: `Map<String, Object>`
- **响应**: `ApiResult<Void>`

### 3.6 获取主控设备实时监控状态

- **接口**: `GET /api/teleop/{controllerId}/monitor`
- **响应**: `ApiResult<Map<String, Object>>`

### 3.7 远程重启主控设备

- **接口**: `POST /api/teleop/{controllerId}/restart`
- **请求体**: `DeviceRestartDTO`
- **响应**: `ApiResult<Void>`

### 3.8 紧急停机主控设备

- **接口**: `POST /api/teleop/{controllerId}/emergency-stop`
- **请求体**: `EmergencyStopDTO`
- **响应**: `ApiResult<Void>`

### 3.9 设置主控设备力反馈及运动参数

- **接口**: `POST /api/teleop/{controllerId}/control-params`
- **请求体**: `MasterControlParamsDTO`

| 字段 | 类型 | 说明 |
|------|------|------|
| armLevel | int | 机械臂力度等级 |
| gripperLevel | int | 夹爪力度等级 |
| moveSpeedLevel | int | 底盘行进速度等级 |
| liftSpeedLevel | int | 身体升降速度等级 |
| operator | string | 操作员标识 |

- **响应**: `ApiResult<Void>`

### 3.10 编辑主控设备

- **接口**: `PUT /api/teleop/{controllerId}`
- **请求体**: `DeviceUpdateDTO`
- **响应**: `ApiResult<Void>`

### 3.11 删除主控设备（逻辑删除）

- **接口**: `DELETE /api/teleop/{controllerId}`
- **响应**: `ApiResult<Void>`

### 3.12 禁用/启用主控设备

- **接口**: `PUT /api/teleop/{controllerId}/status/{status}`
- **Query**: operator（可选）
- **响应**: `ApiResult<Void>`

### 3.13 批量查询主控在线状态

- **接口**: `POST /api/teleop/batch/online-status`
- **请求体**: `List<String>` — 主控ID 列表
- **响应**: `ApiResult<List<Map<String, Object>>>`

### 3.14 主控端登录记录

- **接口**: `POST /api/teleop/login`
- **请求体**: `MasterLoginDTO`

| 字段 | 类型 | 说明 |
|------|------|------|
| deviceId | string | 主控设备ID（与 deviceCode 二选一） |
| deviceCode | string | 主控设备编码 |
| macAddress | string | 主控网卡MAC（登录解析时反查用） |
| operatorId | string | 操作员ID |
| operatorName | string | 操作员姓名 |
| associatedRobotId | string | 当时关联的机器人ID（可选） |
| associatedRobotCode | string | 当时关联的机器人编码（可选） |

- **响应**: `ApiResult<Void>`

### 3.15 登录后同步解析当前主控与机器人

- **接口**: `POST /api/teleop/login/resolve`
- **请求体**: `MasterLoginDTO`（同上）
- **响应**: `ApiResult<MasterLoginResolveVO>`

MasterLoginResolveVO：loginLogId、controller（IotDevice）、currentRobot（RobotBasicVO）、availableRobots、pendingWorkOrder（WorkOrder）。

### 3.16 操作记录分页

- **接口**: `POST /api/teleop/operation-record/page`
- **请求体**: `OperationRecordQueryDTO`

| 字段 | 类型 | 说明 |
|------|------|------|
| pageNo | int | 默认 1 |
| pageSize | int | 默认 10 |
| controllerId | string | 主控ID |
| controllerCode | string | 主控编号 |
| robotId | string | 机器人ID |
| startTimeFrom | string | 开始操作时间起 |
| startTimeTo | string | 开始操作时间止 |

- **响应**: `ApiResult<IPage<MasterOperationRecord>>`

### 3.17 操作记录导出 Excel

- **接口**: `POST /api/teleop/operation-record/export`
- **请求体**: 同 3.16 的 `OperationRecordQueryDTO`（不分页）
- **响应**: 二进制流，文件名 operation_record_*.xlsx

### 3.18 主控使用状态

- **接口**: `GET /api/teleop/usage-status/{controllerCode}`
- **路径参数**: controllerCode
- **响应**: `ApiResult<UsageStatusVO>` — controllerId、controllerCode、lastLoginTime、lastRemoteOperationStartTime、currentDevice、availableRobots

### 3.19 导出主控设备列表 Excel

- **接口**: `POST /api/teleop/export`
- **请求体**: 与 list 相同的 `DeviceRequestDTO`
- **响应**: 二进制流，文件名 controllers_*.xlsx

### 3.20 获取机器人全部摄像头视频流地址

- **接口**: `GET /api/teleop/{deviceId}/camera/stream`
- **路径参数**: deviceId — 机器人设备ID（此处按主控接口路径，但业务校验为机器人）
- **响应**: `ApiResult<List<DeviceCameraStreamVO>>` — cameraIndex、cameraName、streamUrl、streamType

### 3.21 获取机器人指定路数摄像头视频流地址

- **接口**: `GET /api/teleop/{deviceId}/camera/stream/{cameraIndex}`
- **路径参数**: deviceId, cameraIndex（从 0 开始）
- **响应**: `ApiResult<DeviceCameraStreamVO>`

---

## 4. 设备授权管理（/api/device/auth）

**Base Path**: `/realman-iot/api/device/auth`

### 4.1 分页查询设备授权列表

- **接口**: `POST /api/device/auth/page`
- **请求体**: `DeviceAuthQueryDTO`

| 字段 | 类型 | 说明 |
|------|------|------|
| pageNo | int | 默认 1 |
| pageSize | int | 默认 20 |
| subjectType | string | USER / TENANT |
| subjectId | string | 主体ID |
| controllerId | string | 主控ID |
| deviceId | string | 设备ID |
| startEffectiveTime | string | 生效时间起，yyyy-MM-dd HH:mm:ss |
| endEffectiveTime | string | 生效时间止 |
| status | int | 1 启用 0 禁用 |

- **响应**: `ApiResult<IPage<IotDeviceAuth>>`

### 4.2 新增设备授权

- **接口**: `POST /api/device/auth`
- **请求体**: `IotDeviceAuth` 实体字段（subjectType、subjectId、controllerId、deviceId、deviceCode、adminUserId、adminUsername、effectiveTime、expireTime、status 等）
- **响应**: `ApiResult<IotDeviceAuth>`

### 4.3 修改设备授权

- **接口**: `PUT /api/device/auth/{id}`
- **请求体**: `IotDeviceAuth`（含 id）
- **响应**: `ApiResult<IotDeviceAuth>`

### 4.4 删除设备授权（逻辑删除）

- **接口**: `DELETE /api/device/auth/{id}`
- **响应**: `ApiResult<Void>`

### 4.5 导出授权列表 Excel

- **接口**: `POST /api/device/auth/export`
- **请求体**: `DeviceAuthQueryDTO`
- **响应**: 二进制流，文件名 device_auth_*.xlsx

---

## 5. OTA 固件升级（/api/ota）

**Base Path**: `/realman-iot/api/ota`

### 5.1 固件分片上传（断点续传）

- **接口**: `POST /api/ota/firmware/upload/chunk`
- **Content-Type**: multipart/form-data
- **参数**: file（MultipartFile）、uploadId（可选，首次为空则自动生成）、chunkIndex（int）、totalChunks（int）
- **响应**: `ApiResult<String>` — 返回 uploadId（后续合并用）

### 5.2 查询已上传分片列表（断点续传恢复）

- **接口**: `GET /api/ota/firmware/upload/chunks?uploadId=xxx`
- **响应**: `ApiResult<List<Integer>>` — 已上传的 chunkIndex 列表

### 5.3 合并分片并发布固件

- **接口**: `POST /api/ota/firmware/upload/merge`
- **Content-Type**: application/x-www-form-urlencoded 或 multipart（按实现）
- **参数**: uploadId、firmwareName、version、productId、description（可选）
- **响应**: `ApiResult<IotOtaFirmware>`

### 5.4 查询固件列表

- **接口**: `GET /api/ota/firmware/list?pageNo=1&pageSize=10&productId=xxx`
- **响应**: `ApiResult<IPage<IotOtaFirmware>>`

### 5.5 创建 OTA 升级任务

- **接口**: `POST /api/ota/task/create`
- **请求体**: `OtaTaskDTO` — firmwareId（必填）、taskName（必填）、deviceIds（必填，列表）、operator（可选）
- **响应**: `ApiResult<IotOtaUpgradeTask>`

### 5.6 执行 OTA 升级任务

- **接口**: `POST /api/ota/task/{taskId}/execute`
- **响应**: `ApiResult<Void>`

### 5.7 取消 OTA 升级任务

- **接口**: `POST /api/ota/task/{taskId}/cancel?operator=system`
- **响应**: `ApiResult<Void>`

---

## 6. 设备操作日志（/api/log）

**Base Path**: `/realman-iot/api/log`

### 6.1 按设备查询操作日志

- **接口**: `GET /api/log/device/{deviceId}?pageNo=1&pageSize=20&operationType=xxx&startTime=xxx&endTime=xxx`
- **参数**: startTime/endTime 为 ISO 日期时间格式
- **响应**: `ApiResult<IPage<IotDeviceOperationLog>>`

### 6.2 全局操作日志分页

- **接口**: `GET /api/log/list?pageNo=1&pageSize=20&operationType=xxx&startTime=xxx&endTime=xxx`
- **响应**: `ApiResult<IPage<IotDeviceOperationLog>>`

---

## 7. 工单管理（/api/work-order）

**Base Path**: `/realman-iot/api/work-order`

### 7.1 分页查询工单

- **接口**: `POST /api/work-order/page`
- **请求体**: `WorkOrderQueryDTO` — pageNo、pageSize、agentId、status
- **响应**: `ApiResult<IPage<WorkOrder>>`

### 7.2 导出工单列表 Excel

- **接口**: `POST /api/work-order/export`
- **请求体**: `WorkOrderQueryDTO`
- **响应**: 二进制流，文件名 work_order_*.xlsx

### 7.3 创建工单

- **接口**: `POST /api/work-order`
- **请求体**: `WorkOrderCreateDTO` — agentId、agentName、departmentId、departmentName、complianceId、remark、planStartTime、planEndTime、devices（List<WorkOrderDeviceDTO>）
- **WorkOrderDeviceDTO**: deviceType、deviceId、deviceName、deviceCode、actualDeviceId、actualDeviceName、actualDeviceCode
- **响应**: `ApiResult<WorkOrder>`

### 7.4 工单详情

- **接口**: `GET /api/work-order/{id}`
- **响应**: `ApiResult<WorkOrder>`

### 7.5 开始工单

- **接口**: `POST /api/work-order/{id}/start`
- **请求体**: `WorkOrderStartDTO` — operatorId、operatorName、operatorPhone
- **响应**: `ApiResult<Void>`

### 7.6 提交工单

- **接口**: `POST /api/work-order/{id}/submit`
- **响应**: `ApiResult<Void>`

### 7.7 填写超时原因

- **接口**: `POST /api/work-order/{id}/timeout-reason`
- **请求体**: `WorkOrderTimeoutReasonDTO` — reason、source（OPERATOR/ADMIN）
- **响应**: `ApiResult<Void>`

### 7.8 审核工单

- **接口**: `POST /api/work-order/{id}/audit`
- **请求体**: `WorkOrderAuditDTO` — result、comment
- **响应**: `ApiResult<Void>`

### 7.9 关闭工单

- **接口**: `POST /api/work-order/{id}/close`
- **请求体**: `WorkOrderTimeoutReasonDTO` — reason
- **响应**: `ApiResult<Void>`

### 7.10 主控端待开始工单列表

- **接口**: `GET /api/work-order/pending/controller/{controllerCode}`
- **响应**: `ApiResult<List<WorkOrder>>`

### 7.11 新增工单附件

- **接口**: `POST /api/work-order/{id}/attachments`
- **请求体**: `List<WorkOrderAttachmentDTO>` — fileName、fileUrl、description
- **响应**: `ApiResult<Void>`

---

## 8. 工单合规配置（/api/work-order/compliance）

**Base Path**: `/realman-iot/api/work-order/compliance`

### 8.1 分页查询工单合规配置

- **接口**: `POST /api/work-order/compliance/page`
- **请求体**: `WorkOrderComplianceQueryDTO` — pageNo、pageSize、agentId、status
- **响应**: `ApiResult<IPage<WorkOrderComplianceConfig>>`

### 8.2 新增工单合规配置

- **接口**: `POST /api/work-order/compliance`
- **请求体**: `WorkOrderComplianceConfig` 实体
- **响应**: `ApiResult<WorkOrderComplianceConfig>`

### 8.3 修改工单合规配置

- **接口**: `PUT /api/work-order/compliance/{id}`
- **请求体**: `WorkOrderComplianceConfig`（已启用的不允许编辑）
- **响应**: `ApiResult<WorkOrderComplianceConfig>`

### 8.4 删除工单合规配置（逻辑删除）

- **接口**: `DELETE /api/work-order/compliance/{id}`（已启用的不允许删除）
- **响应**: `ApiResult<Void>`

### 8.5 导出工单合规配置 Excel

- **接口**: `POST /api/work-order/compliance/export`
- **请求体**: `WorkOrderComplianceQueryDTO`
- **响应**: 二进制流，文件名 work_order_compliance_*.xlsx

---

## 9. 内部接口（仅供 EMQX 回调，联调可不关注）

- **POST /realman-iot/internal/mqtt/auth** — EMQX 认证回调，body: clientid、username、password、peerhost，返回 `{"result":"allow"|"deny"}`。
- **POST /realman-iot/internal/mqtt/acl** — EMQX ACL 回调，body: clientid、username、topic、action，返回 `{"result":"allow"|"deny"}`。

---

## 10. 实体字段速查

### IotDevice（设备）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 主键 |
| deviceCode | string | 设备编号 |
| deviceName | string | 设备名称 |
| deviceType | int | 1-机器人 2-主控 |
| productId | string | 产品ID |
| deviceModel | string | 型号 |
| serialNumber | string | 序列号 |
| macAddress | string | MAC |
| firmwareVersion | string | 固件版本 |
| status | int | 0未激活 1在线 2离线 3禁用 |
| description | string | 描述 |
| lastOnlineTime | datetime | 最后在线时间 |
| lastOfflineTime | datetime | 最后离线时间 |
| lastLoginTime | datetime | 主控最后登录时间 |
| longitude / latitude | decimal | 经纬度 |
| createTime / updateTime | datetime | 创建/更新时间 |

### WorkOrder（工单）常用字段

id、agentId、agentName、departmentId、departmentName、complianceId、remark、planStartTime、planEndTime、status（如 PENDING/IN_PROGRESS/SUBMITTED 等）、auditResult、operatorId、operatorName、operatorPhone、actualStartTime、submitTime、timeoutReason、auditBy、auditTime、auditComment、closeBy、closeTime、closeReason、createTime、updateTime。

### IotDeviceAuth（设备授权）常用字段

id、subjectType、subjectId、controllerId、deviceId、deviceCode、adminUserId、adminUsername、effectiveTime、expireTime、status、createTime、updateTime。

---

**文档版本**：基于当前 realman-boot-iot 代码生成，用于前后端联调。若接口有变更，请以实际代码为准并同步更新本文档。
