# realman-system 接口文档（前后端联调）

本文档用于 realman-system（jeecg-system）与前端联调，描述系统模块对外提供的 HTTP 接口、请求方式及统一响应格式。系统模块包含：登录鉴权、用户/部门/角色/权限、字典、租户等。

---

## 1. 基础说明

### 1.1 服务信息

| 项 | 说明 |
|----|------|
| 应用名 | jeecg-system（realman-boot-system） |
| 默认端口 | 8080（以 application-dev 为准） |
| 上下文路径 | `/realman-boot`（dev 环境） |
| 接口根地址 | `http://{host}:8080/realman-boot` |

### 1.2 统一响应格式

接口统一返回 JSON：`Result<T>`（`org.jeecg.common.api.vo.Result`）

```json
{
  "success": true,
  "message": "",
  "code": 200,
  "result": { ... },
  "timestamp": 1734567890123
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 提示信息 |
| code | int | 200 成功，500 失败，401 无权限等 |
| result | object/array/null | 业务数据（注意字段名为 result，非 data） |
| timestamp | long | 时间戳 |

分页接口中，`result` 常为 `IPage` 结构：含 `records`、`total`、`size`、`current`、`pages` 等。

### 1.3 鉴权

- 除登录、验证码、短信等白名单接口外，需在请求头携带 **JWT Token**（如 `X-Access-Token` 或 `Authorization`，以项目配置为准）。
- 部分接口使用 `@RequiresPermissions`、`@RequiresRoles` 做权限控制；多租户场景会按当前租户隔离数据。

---

## 2. 登录与用户信息（/sys）

**Base Path**: `/realman-boot/sys`  
**Controller**: LoginController

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /sys/login | 登录，请求体 SysLoginModel（username、password、captcha、checkKey 等） |
| GET | /sys/user/getUserInfo | 根据 Token 获取当前用户信息 |
| GET/POST | /sys/logout | 登出 |
| GET | /sys/loginfo | 登录日志信息 | 
| GET | /sys/randomImage/{key} | 获取验证码图片，key 为验证码唯一标识 |
| POST | /sys/checkCaptcha | 校验验证码 |
| POST | /sys/loginGetUserDeparts | 登录后获取用户部门列表 |

---

## 3. 用户管理（/sys/user）

**Base Path**: `/realman-boot/sys/user`  
**Controller**: SysUserController

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/user/list | 分页查询用户（租户隔离），Query 参数：pageNo、pageSize 及 SysUser 条件 |
| GET | /sys/user/listAll | 分页查询全部用户（不做租户隔离，需权限） |
| POST | /sys/user/add | 新增用户，Body：JSON（含 selectedroles、selecteddeparts、relTenantIds 等） |
| PUT/POST | /sys/user/edit | 编辑用户，Body：JSON（id、selectedroles、selecteddeparts、relTenantIds 等） |
| POST | /sys/user/addTenantUser | 添加租户用户（租户模式专用） |
| DELETE | /sys/user/delete | 删除用户 |
| DELETE | /sys/user/deleteBatch | 批量删除 |
| PUT | /sys/user/resetPassword | 重置密码 |
| GET | /sys/user/queryById | 根据 ID 查询用户 |
| GET | /sys/user/queryUserRole | 查询用户角色 |
| GET | /sys/user/checkOnlyUser | 校验用户名唯一 |
| PUT | /sys/user/changePassword | 修改密码 |
| GET | /sys/user/userDepartList | 用户部门列表 |
| GET | /sys/user/generateUserId | 生成用户 ID |
| GET | /sys/user/queryUserByDepId | 按部门查用户 |
| GET | /sys/user/exportXls | 导出用户 Excel |
| POST | /sys/user/importExcel | 导入用户 Excel |
| GET | /sys/user/queryByIds | 根据 ID 列表查询 |
| GET | /sys/user/queryByNames | 根据用户名列表查询 |
| GET | /sys/user/queryUserAndDeptByName | 根据用户名查用户及部门 |
| PUT | /sys/user/updatePassword | 更新密码 |
| GET | /sys/user/userRoleList | 用户角色列表 |
| POST | /sys/user/addSysUserRole | 添加用户角色 |
| DELETE | /sys/user/deleteUserRole | 删除用户角色 |
| DELETE | /sys/user/deleteUserRoleBatch | 批量删除用户角色 |
| GET | /sys/user/departUserList | 部门用户列表 |
| GET | /sys/user/getUserDetailByUserId | 用户详情（含部门等） |
| POST | /sys/user/editSysDepartWithUser | 编辑部门与用户关系 |
| DELETE | /sys/user/deleteUserInDepart | 从部门移除用户 |
| DELETE | /sys/user/deleteUserInDepartBatch | 批量从部门移除 |
| GET | /sys/user/getCurrentUserDeparts | 当前用户部门列表 |

---

## 4. 企业部门管理（/sys/sysDepart）

**Base Path**: `/realman-boot/sys/sysDepart`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/sysDepart/queryMyDeptTreeList | 当前用户部门树 |
| GET | /sys/sysDepart/queryTreeList | 部门树 |
| GET | /sys/sysDepart/queryDepartTreeSync | 部门树同步 |
| GET | /sys/sysDepart/queryDepartAndPostTreeSync | 部门与岗位树同步 |
| GET | /sys/sysDepart/queryAllParentId | 查询所有父级 ID |
| POST | /sys/sysDepart/add | 新增部门 |
| PUT/POST | /sys/sysDepart/edit | 编辑部门 |
| DELETE | /sys/sysDepart/delete | 删除部门 |
| DELETE | /sys/sysDepart/deleteBatch | 批量删除 |
| GET | /sys/sysDepart/queryIdTree | 部门 ID 树 |
| GET | /sys/sysDepart/searchBy | 条件搜索 |
| GET | /sys/sysDepart/exportXls | 导出 Excel |
| POST | /sys/sysDepart/importExcel | 导入 Excel |
| GET | /sys/sysDepart/listAll | 全部部门列表 |
| GET | /sys/sysDepart/queryTreeByKeyWord | 关键词查部门树 |
| GET | /sys/sysDepart/getDepartName | 获取部门名称 |
| GET | /sys/sysDepart/getUsersByDepartId | 按部门查用户 |
| GET | /sys/sysDepart/queryByIds | 按 ID 列表查询 |
| GET | /sys/sysDepart/getUsersByDepartTenantId | 按部门租户查用户 |

---

## 5. 角色管理（/sys/role）

**Base Path**: `/realman-boot/sys/role`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/role/list | 分页角色列表 |
| GET | /sys/role/listByTenant | 按租户分页角色列表 |
| POST | /sys/role/add | 新增角色 |
| PUT/POST | /sys/role/edit | 编辑角色 |
| DELETE | /sys/role/delete | 删除角色 |
| DELETE | /sys/role/deleteBatch | 批量删除 |
| GET | /sys/role/queryById | 根据 ID 查询 |
| GET | /sys/role/queryall | 查询全部角色 |
| GET | /sys/role/queryallNoByTenant | 查询全部角色（不按租户） |
| GET | /sys/role/checkRoleCode | 校验角色编码 |
| GET | /sys/role/exportXls | 导出 Excel |
| POST | /sys/role/importExcel | 导入 Excel |
| GET | /sys/role/datarule/{permissionId}/{roleId} | 数据规则 |
| POST | /sys/role/datarule | 保存数据规则 |
| GET | /sys/role/queryTreeList | 角色树列表 |
| GET | /sys/role/queryPageRoleCount | 分页角色数量 |

---

## 6. 菜单权限（/sys/permission）

**Base Path**: `/realman-boot/sys/permission`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/permission/list | 权限列表 |
| GET | /sys/permission/getSystemMenuList | 系统菜单列表 |
| GET | /sys/permission/getSystemSubmenu | 系统子菜单 |
| GET | /sys/permission/getSystemSubmenuBatch | 批量获取子菜单 |
| GET | /sys/permission/queryByUser | 按用户查权限 |
| GET | /sys/permission/getUserPermissionByToken | 按 Token 取用户权限 |
| GET | /sys/permission/getPermCode | 权限编码 |
| POST | /sys/permission/add | 新增权限/菜单 |
| PUT/POST | /sys/permission/edit | 编辑 |
| GET | /sys/permission/checkPermDuplication | 校验权限重复 |
| DELETE | /sys/permission/delete | 删除 |
| DELETE | /sys/permission/deleteBatch | 批量删除 |
| GET | /sys/permission/queryTreeList | 权限树 |
| GET | /sys/permission/queryListAsync | 异步树列表 |
| GET | /sys/permission/queryRolePermission | 角色权限 |
| POST | /sys/permission/saveRolePermission | 保存角色权限 |
| GET | /sys/permission/getPermRuleListByPermId | 权限规则列表 |
| POST | /sys/permission/addPermissionRule | 新增权限规则 |
| PUT/POST | /sys/permission/editPermissionRule | 编辑权限规则 |
| DELETE | /sys/permission/deletePermissionRule | 删除权限规则 |
| GET | /sys/permission/queryPermissionRule | 查询权限规则 |

---

## 7. 字典（/sys/dict、/sys/dictItem）

### 7.1 字典主表（/sys/dict）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/dict/list | 字典分页列表 |
| GET | /sys/dict/treeList | 字典树列表 |
| GET | /sys/dict/queryAllDictItems | 查询所有字典项 |
| GET | /sys/dict/getDictText/{dictCode}/{key} | 根据字典编码与 key 取文本 |
| GET | /sys/dict/getDictItems/{dictCode} | 根据字典编码取字典项 |
| GET | /sys/dict/loadDict/{dictCode} | 加载字典 |
| GET | /sys/dict/loadDictOrderByValue/{dictCode} | 按 value 排序加载 |
| GET | /sys/dict/loadDictItem/{dictCode} | 加载字典项 |
| GET | /sys/dict/loadTreeData | 加载树形数据 |
| GET | /sys/dict/queryTableData | 查询表数据 |
| POST | /sys/dict/add | 新增字典 |
| PUT/POST | /sys/dict/edit | 编辑 |
| DELETE | /sys/dict/delete | 删除 |
| DELETE | /sys/dict/deleteBatch | 批量删除 |
| GET | /sys/dict/refleshCache | 刷新缓存 |
| GET | /sys/dict/exportXls | 导出 |
| POST | /sys/dict/importExcel | 导入 |
| GET | /sys/dict/deleteList | 删除列表（回收站） |
| DELETE | /sys/dict/deletePhysic/{id} | 物理删除 |
| PUT | /sys/dict/back/{id} | 从回收站恢复 |
| PUT | /sys/dict/putRecycleBin | 放入回收站 |
| DELETE | /sys/dict/deleteRecycleBin | 回收站删除 |
| GET | /sys/dict/getDictListByLowAppId | 按低代码应用取字典 |
| POST | /sys/dict/addDictByLowAppId | 低代码新增字典 |
| PUT | /sys/dict/editDictByLowAppId | 低代码编辑字典 |

### 7.2 字典项（/sys/dictItem）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/dictItem/list | 字典项列表 |
| POST | /sys/dictItem/add | 新增 |
| PUT/POST | /sys/dictItem/edit | 编辑 |
| DELETE | /sys/dictItem/delete | 删除 |
| DELETE | /sys/dictItem/deleteBatch | 批量删除 |
| GET | /sys/dictItem/dictItemCheck | 字典项唯一性校验 |

---

## 8. 租户（/sys/tenant）

**Base Path**: `/realman-boot/sys/tenant`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/tenant/list | 租户分页列表 |
| GET | /sys/tenant/recycleBinPageList | 回收站分页 |
| POST | /sys/tenant/add | 新增租户 |
| POST | /sys/tenant/syncDefaultPack | 同步默认产品包 |
| PUT/POST | /sys/tenant/edit | 编辑租户 |
| DELETE/POST | /sys/tenant/delete | 删除租户 |
| DELETE | /sys/tenant/deleteBatch | 批量删除 |
| GET | /sys/tenant/queryById | 根据 ID 查询 |
| GET | /sys/tenant/queryList | 租户列表 |
| GET | /sys/tenant/getCurrentUserTenant | 当前用户租户 |
| GET | /sys/tenant/getTenantUserList | 租户用户列表 |
| GET | /sys/tenant/getTenantListByUserId | 按用户查租户列表 |

---



## 9. 操作日志（/sys/log）

**Base Path**: `/realman-boot/sys/log`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sys/log/list | 日志分页列表 |
| DELETE | /sys/log/delete | 删除 |
| DELETE | /sys/log/deleteBatch | 批量删除 |
| GET | /sys/log/exportXls | 导出 Excel |

---


## 10. 请求说明补充

- **分页**：多数 list 接口支持 Query 参数 `pageNo`、`pageSize`（默认常见为 1、10）。
- **条件查询**：列表类接口往往通过 Query 或 Body 传入实体字段做模糊/精确筛选（与 JeecgBoot QueryGenerator 约定一致）。
- **导入导出**：`exportXls` 多为 GET，返回文件流；`importExcel` 为 POST，Body 为 multipart 文件。
- **Token**：登录成功后返回的 `token` 需在后续请求头中携带（具体 header 名以项目配置为准，如 `X-Access-Token`）。

---

