# realman-boot 现场部署运维手册

> 版本：v1.3.0 | 日期：2026-06-10  
> 适用对象：**运维支持 / 技术支持**  
> 典型环境：**客户云服务器**（阿里云 / 京东云 ECS + 云数据库 RDS，推荐）或本地私有化（§5 / 场景 C）  
> 部署方式：单机 Docker Compose  
> **本文档自包含全部配置文件与命令，现场部署无需查阅其他文档。**  
> **首次部署建议：** 先读 [§0 部署总览](#0-部署总览零基础必读)，再按 [§14 分阶段部署](#14-标准部署流程分阶段) 逐步执行，每阶段完成「通过标准」后再进入下一阶段。

---

## 占位符说明（全文统一替换）


| 占位符                                 | 含义                                | 示例                            |
| ----------------------------------- | --------------------------------- | ----------------------------- |
| `<SERVER_IP>`                       | 浏览器/设备访问用的 IP 或域名                 | `192.168.1.100`               |
| `<DB_HOST>`                         | 数据库主机                             | 云 RDS 内网域名，或 `realman-mysql`  |
| `<DB_USER>` / `<DB_PASSWORD>`       | 数据库账号密码                           |                               |
| `<MYSQL_ROOT_PASSWORD>`             | 本机 MySQL root 密码（场景 C）            |                               |
| `<REDIS_PASSWORD>`                  | Redis requirepass                 |                               |
| `<MINIO_USER>` / `<MINIO_PASSWORD>` | MinIO 管理员                         | 默认 `minioadmin` / 现场强密码       |
| `<MQTT_PASSWORD>`                   | EMQX 用户 `iot-platform` 密码         |                               |
| `<EMQX_API_PASSWORD>`               | EMQX Dashboard 密码或 API Key Secret | 与 `mqtt.emqx.api-password` 一致 |
| `<SIGNATURE_SECRET>`                | `jeecg.signatureSecret` 生产密钥      |                               |


## 目录

1. [部署总览（零基础必读）](#0-部署总览零基础必读)
2. [使用说明与场景选择](#1-使用说明与场景选择)
3. [出发前信息收集](#2-出发前信息收集)
4. [环境与规格](#3-环境与规格)（含 [§3.4 云服务器专章](#34-云服务器部署专章)）
5. [云数据库准备（场景 A/B）](#4-云数据库准备场景-ab)
6. [本机 MySQL 准备（场景 C）](#5-本机-mysql-准备场景-c)
7. [服务器初始化](#6-服务器初始化)
8. [目录结构](#7-目录结构)
9. [完整配置文件](#8-完整配置文件)
10. [Docker Compose](#9-docker-compose)
11. [Dockerfile](#10-dockerfile)
12. [Nacos 生产配置](#11-nacos-生产配置)
13. [EMQX 初始化与一机一密](#12-emqx-初始化与一机一密)
14. [应用构建与镜像打包](#13-应用构建与镜像打包)
15. [标准部署流程](#14-标准部署流程)
16. [部署验证](#15-部署验证)
17. [回滚方案](#16-回滚方案)
18. [现场故障排查](#17-现场故障排查)
19. [日常运维](#18-日常运维)
20. [客户交付清单](#19-客户交付清单)
21. [附录](#20-附录)

---

## 0. 部署总览（零基础必读）

### 0.1 系统是什么

realman-boot 是一套 **机器人/IoT 管理平台**，单机 Docker Compose 部署，包含：


| 层次   | 组件                             | 作用                        |
| ---- | ------------------------------ | ------------------------- |
| 业务应用 | `realman-system`、`realman-iot` | 管理后台 API、设备 MQTT 业务、工单等   |
| 配置中心 | Nacos                          | 应用 JDBC/Redis/MQTT 等运行时配置 |
| 消息   | EMQX                           | 设备 MQTT 接入（1883）          |
| 存储   | MinIO、MySQL/RDS、Redis          | 文件、业务数据、缓存                |
| 入口   | Nginx :80                      | 统一 Web/API/控制台反向代理        |
| 可观测  | Loki、Grafana、Zipkin、XXL-Job    | 日志、链路、定时任务                |


**你不需要理解全部业务代码**，只需：准备云资源 → 写配置文件 → 导入 SQL → 按顺序启动容器 → 在控制台做 EMQX/Nacos 初始化 → 验收。

### 0.2 部署路线图（首次约 2～4 小时）

```
阶段 0  信息收集 + 交付包校验          → §2、§2.3
阶段 1  云资源 / 服务器 / Docker       → §3、§6
阶段 2  数据库连通 + 导入初始化 SQL     → §4 或 §5
阶段 3  创建目录 + 写入全部配置文件     → §7、§8、§9
阶段 4  启动中间件（Redis→Nacos→…）    → §14.4～14.6
阶段 5  控制台：Nacos 配置 + EMQX + MinIO → §11、§12
阶段 6  构建/上传应用镜像并启动         → §13、§14.8
阶段 7  全量验收 + 交付清单            → §15、§19
```

**每阶段必须通过「通过标准」再往下走**，避免中间件未就绪就启应用导致反复排错。

### 0.3 场景怎么选（30 秒决策）


| 你的环境                     | 选场景       | 数据库连接                            | Compose                    |
| ------------------------ | --------- | -------------------------------- | -------------------------- |
| 阿里云/京东云 ECS + 云 RDS      | **A / B** | `.env` 里 `RDS_HOST`=RDS **内网地址** | [§9.1](#91-场景-ab云-rds-版)   |
| 单机自建，MySQL 也跑在同一台 Docker | **C**     | Nacos 里写 `realman-mysql`         | [§9.2](#92-场景-c本机-mysql-版) |


> **默认推荐场景 A/B**：数据库用云 RDS，应用与中间件在同一台 ECS。

### 0.4 五个最容易踩坑的点（必读）


| #   | 坑                            | 正确做法                                                                                                                 |
| --- | ---------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 1   | 前端 API 404                   | Nginx 必须是 `location /realman-boot`，**不是** `/realman-system`；Nacos **禁止**写 `context-path`                             |
| 2   | RDS 连不上                      | 用 RDS **内网域名**，白名单加 ECS **内网 IP**                                                                                    |
| 3   | IoT 启动失败                     | Nacos `realman-iot.yaml` 须含 `spring.cloud.nacos.discovery`；未部署 Darwin/RocketMQ 时 `darwin.integration.enabled: false` |
| 4   | Nginx 起不来                    | 使用 §8.9 带 `resolver` 的配置；或 **先启应用再启 Nginx**                                                                          |
| 5   | EMQX/Loki/Grafana 反复 Restart | 首次启动前执行 §6.4 数据目录权限                                                                                                  |


### 0.5 交付包自检（到现场前）

```
[ ] realman-system-start-*.jar、realman-boot-iot-start-*.jar（或已构建好的 Docker 镜像 tar）
[ ] 两个 Dockerfile（§10）
[ ] 四份 SQL：realman_boot_init.sql、nacos_init.sql、tables_xxl_job.sql、iot_init.sql
[ ] 前端 gln_admin、gln_teleop（若含前端）
[ ] 本手册 §8、§9、§11 模板已准备好现场密码替换
```

---

## 1. 使用说明与场景选择


| 场景            | 典型情况            | 数据库         | 使用 Compose                            |
| ------------- | --------------- | ----------- | ------------------------------------- |
| **A. 阿里云**    | ECS + RDS MySQL | 云 RDS       | [§9.1 云 RDS 版](#91-场景-ab云-rds-版)      |
| **B. 京东云**    | 云主机 + 云数据库      | 云 RDS       | [§9.1 云 RDS 版](#91-场景-ab云-rds-版)      |
| **C. 本地/私有化** | 物理机/虚拟机         | 本机 MySQL 容器 | [§9.2 本机 MySQL 版](#92-场景-c本机-mysql-版) |


> **客户使用云服务器时，默认走场景 A 或 B**（应用与中间件在 ECS，MySQL 用云 RDS）。场景 C 仅在没有云数据库时使用。

```
客户使用云服务器？
    ├─ 是 → 有云数据库 RDS？
    │         ├─ 是 → 场景 A/B → §3.4 + §4 + §8.7 + §9.1 + §11.1/11.2
    │         └─ 否 → 场景 C（云主机单机 MySQL，不推荐）→ §5 + §9.2
    └─ 否（机房/本地）→ 场景 C → §5 + §9.2
```

### 1.1 命名对照（易混淆，必读）


| 名称                    | 值                     | 说明                                |
| --------------------- | --------------------- | --------------------------------- |
| Docker 容器/服务名         | `realman-system`      | compose、Nginx upstream            |
| **前端 API 前缀**         | `**/realman-boot`**   | 浏览器、Nginx location，**勿改**         |
| Spring `context-path` | `/realman-boot`       | jar 内 `application.yml` 固定        |
| Nacos 配置 DataId       | `realman-system.yaml` | 仅配置中心文件名                          |
| Nacos 注册名             | `realman-boot`        | `spring.application.name`，Feign 用 |
| IoT API 前缀            | `/realman-iot`        | 前后端一致                             |


> Nacos `realman-system.yaml` 中 **禁止** 配置 `server.servlet.context-path: /realman-system`，否则前端 `/realman-boot` 会 404。

**现场原则：**

1. 部署前填完 [§2 信息收集表](#2-出发前信息收集)。
2. 密码、密钥一律用客户现场专用值，禁止拷贝公司测试环境配置。
3. 持久化目录统一 `/opt/realman`。
4. 所有 `<占位符>` 替换完毕后再启动服务。

---

## 2. 出发前信息收集

### 2.1 服务器与网络（云服务器必填）


| 项目                              | 填写                               | 备注                    |
| ------------------------------- | -------------------------------- | --------------------- |
| 云厂商                             | □ 阿里云 □ 京东云 □ 本地/机房              |                       |
| 地域 / 可用区                        | ____________                     | ECS 与 RDS **必须同地域**   |
| VPC / 交换机                       | ____________                     | ECS 与 RDS **必须同 VPC** |
| ECS 实例 ID                       | ____________                     |                       |
| 服务器 OS                          | □ Ubuntu 22.04 □ CentOS 7/8 □ 其他 | 命令以 Ubuntu 22.04 为主   |
| SSH                             | `root@___`_________              | 建议密钥登录                |
| **内网 IP**                       | ____________                     | RDS 白名单、安全组           |
| **公网 IP / EIP** → `<SERVER_IP>` | ____________                     | 浏览器、设备 MQTT           |
| 域名（如有）                          | ____________                     |                       |
| 系统盘 / 数据盘                       | ____ GB / ____ GB                | 数据盘 ≥200GB，见 §3.4.3   |
| ECS 安全组 ID                      | ____________                     | 见 §3.2、§3.4.2         |
| RDS 实例 ID                       | ____________                     | 场景 A/B 必填             |
| RDS **内网地址** → `<DB_HOST>`      | ____________                     | **禁止**用 RDS 公网地址      |


### 2.2 数据库


| 项目                            | 填写                                     |
| ----------------------------- | -------------------------------------- |
| 类型                            | □ 云 RDS □ 本机 MySQL                     |
| `<DB_HOST>`                   | ____________                           |
| `<DB_USER>` / `<DB_PASSWORD>` | ____________                           |
| 三库是否已存在                       | `realman-boot` / `nacos` / `xxl_job` □ |


### 2.3 交付包清单

- `realman-system-start-*.jar`、`realman-boot-iot-start-*.jar`
- Dockerfile（§10）
- **数据库初始化 SQL**（首次部署必带，路径见下表）
- 迭代迁移 SQL（正向 + 回滚，放 `app/sql/migrations/`）
- 前端 `gln_teleop`、`gln_admin`（如含前端）
- Git SHA：`___`_________
- （离线/无外网）Docker 镜像 tar 包或客户私有镜像仓库地址


| SQL 文件      | 仓库路径                                | 导入目标库          |
| ----------- | ----------------------------------- | -------------- |
| 业务库初始化      | `db/realman_boot_init.sql`          | `realman-boot` |
| Nacos 表结构   | `db/nacos_init.sql`                 | `nacos`        |
| XXL-Job 表结构 | `db/tables_xxl_job.sql`             | `xxl_job`      |
| IoT 表结构     | `realman-boot-iot/sql/iot_init.sql` | `realman-boot` |


---

## 3. 环境与规格

### 3.1 推荐硬件


| 方案          | 内存合计   | 推荐规格                |
| ----------- | ------ | ------------------- |
| 云 RDS（A/B）  | ~9 GB  | 4C / 16G，数据盘 200GB+ |
| 本机 MySQL（C） | ~11 GB | 8C / 32G，数据盘 200GB+ |


### 3.2 防火墙 / 安全组端口

**云 ECS 在「安全组」入方向配置**（阿里云：ECS → 安全组；京东云：云主机 → 安全组）。


| 端口   | 用途             | 开放建议             |
| ---- | -------------- | ---------------- |
| 22   | SSH 运维         | **源 IP 仅限运维 IP** |
| 80   | Nginx 统一入口     | 公网或客户网段          |
| 1883 | EMQX MQTT TCP  | **设备接入必开**       |
| 8083 | EMQX WebSocket | 按需               |
| 8883 | EMQX TLS       | 有证书时             |


**安全组勿对公网开放：** 6379、8848、18083、3000、9411、9080、9001、3100、8080、8085。  
**云 RDS 3306：** 在 **RDS 白名单** 加 ECS **内网 IP**；不在 ECS 安全组开放 3306。

### 3.3 架构

```
Internet / 内网
  ├─ MQTT ──► EMQX :1883/:8083
  └─ Web ──► Nginx :80 ──► realman-system :8080 / realman-iot :8085
                │
    Redis / Nacos / EMQX / MinIO / XXL-Job
                │
    MySQL：云 RDS（A/B）或 realman-mysql（C）
                │
    Loki / Zipkin / Grafana
```

### 3.4 云服务器部署专章

本节适用于 **场景 A/B**（客户云 ECS + 云 RDS），现场实施按顺序核对。

#### 3.4.1 云资源创建清单


| 资源           | 规格建议                | 关键约束                    |
| ------------ | ------------------- | ----------------------- |
| ECS          | 4C / 16G，数据盘 200GB+ | 与 RDS **同 VPC、同地域**     |
| RDS MySQL    | 8.0，2C4G 起          | 字符集 utf8mb4；三库见 §4      |
| 弹性公网 IP（EIP） | 按带宽或按量              | 绑定 ECS，作为 `<SERVER_IP>` |
| 安全组          | 见 §3.2              | 先收紧 22，再开 80/1883       |


#### 3.4.2 安全组配置示例（阿里云）

```
入方向：
  22/TCP    源=<运维IP>/32        SSH
  80/TCP    源=0.0.0.0/0          Web（可按需收紧）
  1883/TCP  源=0.0.0.0/0          设备 MQTT（可按需收紧）
出方向：默认全部允许（拉 Docker 镜像需要）
```

京东云：云主机 → 安全组 → 入站规则，端口与上表相同。

#### 3.4.3 数据盘挂载（推荐）

云 ECS 系统盘通常 40~100GB，日志/Loki/MinIO 建议放数据盘：

```bash
# 假设数据盘设备 /dev/vdb，按现场 fdisk -l 确认
sudo mkfs.ext4 /dev/vdb
sudo mkdir -p /data
echo '/dev/vdb /data ext4 defaults 0 0' | sudo tee -a /etc/fstab
sudo mount -a
sudo mkdir -p /data/realman
sudo ln -sfn /data/realman /opt/realman   # 或直接把 §7 目录建在 /data/realman
```

#### 3.4.4 RDS 白名单


| 云厂商 | 操作路径                                              |
| --- | ------------------------------------------------- |
| 阿里云 | RDS 控制台 → 实例 → **数据安全性** → 白名单 → 添加 ECS **内网 IP** |
| 京东云 | 云数据库 → 白名单 / 访问控制 → 添加云主机 **内网 IP**               |


> 白名单填 **ECS 内网 IP**（如 `172.16.0.5`），不是 EIP。也可填 VPC 网段（如 `172.16.0.0/16`）便于同 VPC 扩容。

#### 3.4.5 Docker 安装与镜像拉取

**国内 ECS 安装 Docker：** 见 [§6.2](#62-国内云阿里云--京东云等推荐)，勿直接用 `get.docker.com`（易 `Connection reset by peer`）。

若已提前准备镜像包可离线 `docker load`；否则安装后配置镜像加速再 `compose up`：

```bash
docker load -i realman-images.tar
```

国内云**安装后**配置镜像加速（`/etc/docker/daemon.json`）：

```json
{
  "registry-mirrors": ["https://<阿里云ACR镜像加速器地址>"]
}
```

> 阿里云：控制台 → 容器镜像服务 → 镜像工具 → 镜像加速器。配置后 `sudo systemctl restart docker`。

#### 3.4.6 时区

```bash
sudo timedatectl set-timezone Asia/Shanghai
timedatectl
```

#### 3.4.7 云场景常见遗漏


| 遗漏项                       | 后果                | 处理                                        |
| ------------------------- | ----------------- | ----------------------------------------- |
| RDS 用了公网地址                | 连不上或产生公网流量费       | `.env` 改 **内网地址**                         |
| 未导入 `nacos_init.sql`      | Nacos 启动失败        | §4.4                                      |
| 未导入业务/IoT 初始化 SQL         | 登录/设备功能异常         | §4.4                                      |
| 安全组未开 1883                | 设备 MQTT 连不上       | §3.4.2                                    |
| Nacos 未录入双 DataId         | 应用 JDBC/Redis 错误  | §11                                       |
| IoT 缺 Nacos Discovery     | IoT 注册失败退出        | §11.2                                     |
| IoT 未关 Darwin 且无 RocketMQ | IoT Bean 依赖失败     | §11.2 `darwin.integration.enabled: false` |
| 未配 EMQX Management API    | IoT 401 WARN、对账为空 | §11.2、§12.1                               |
| Loki/EMQX/Grafana 权限      | 容器反复 Restarting   | §6.4.2                                    |
| 8080 被其他服务占用              | system 无法启动       | §6.4.1                                    |
| Nginx 无 resolver          | Nginx 启动失败        | §8.9                                      |
| 前端 base 与 Nginx 不一致       | 静态资源 404          | 前端 `base=/gln_admin/`                     |


---

## 4. 云数据库准备（场景 A/B）

### 4.1 阿里云 RDS

1. RDS MySQL 8.0，与应用机**同 VPC**。
2. 创建库：`realman-boot`、`nacos`、`xxl_job`（utf8mb4）。
3. 白名单加入应用机**内网 IP**。
4. 记录**内网地址** → `<DB_HOST>`。

### 4.2 京东云云数据库

与阿里云逻辑相同：云主机 + 云数据库同 VPC，白名单加内网 IP，连接地址用**内网域名**。

### 4.3 连通性验证（必做）

```bash
export RDS_HOST=<DB_HOST>
export RDS_PORT=3306
export RDS_USER=<DB_USER>
export RDS_PASSWORD=<DB_PASSWORD>

docker run --rm mysql:8.0 \
  mysqladmin -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} ping
# 预期：mysqld is alive

docker run --rm mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} \
  -e "SHOW DATABASES;" | grep -E "realman-boot|nacos|xxl_job"
```


| 连接超时     | 处理     |
| -------- | ------ |
| 用了公网地址   | 改内网地址  |
| 白名单未加 IP | 控制台添加  |
| 不同 VPC   | 改网络或专线 |


### 4.4 云 RDS 首次初始化 SQL（必做）

云 RDS 通常只有空库，**须在启动 Nacos 前**导入表结构与初始数据。在 ECS 上执行（SQL 已上传至 `/opt/realman/app/sql/`）：

```bash
source /opt/realman/app/.env

# 创建三库（若 RDS 控制台已建库可跳过）
docker run --rm mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} << 'SQL'
CREATE DATABASE IF NOT EXISTS `realman-boot` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nacos DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

# 导入（路径相对 app/sql/）
docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} nacos \
  < /sql/nacos_init.sql

docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} xxl_job \
  < /sql/tables_xxl_job.sql

docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} realman-boot \
  < /sql/realman_boot_init.sql

docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} realman-boot \
  < /sql/iot_init.sql

echo "云 RDS 初始化完成"
```

上传 SQL 示例（公司侧）：

```bash
scp db/nacos_init.sql db/tables_xxl_job.sql db/realman_boot_init.sql \
    realman-boot-iot/sql/iot_init.sql \
    root@<SERVER_IP>:/opt/realman/app/sql/
```

---

## 5. 本机 MySQL 准备（场景 C）

1. 使用 [§9.2](#92-场景-c本机-mysql-版) compose。
2. 创建目录：`mkdir -p /opt/realman/mysql/{data,conf,logs}`
3. 写入 [§8.2 my.cnf](#82-mysql-配置场景-c)
4. 首次启动并初始化：

```bash
cd /opt/realman/app
docker compose up -d mysql
until docker inspect realman-mysql --format='{{.State.Health.Status}}' | grep -q healthy; do
  sleep 3; echo "等待 MySQL..."
done

docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' << 'SQL'
CREATE DATABASE IF NOT EXISTS `realman-boot` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nacos DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' realman-boot < ./sql/realman_boot_init.sql
docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' nacos         < ./sql/nacos_init.sql
docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' xxl_job       < ./sql/tables_xxl_job.sql
docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' realman-boot < ./sql/iot_init.sql
```

Nacos JDBC 主机写 `realman-mysql`，**不是** `localhost`。

---

## 6. 服务器初始化

> **国内阿里云/京东云 ECS：请直接执行 [§6.2](#62-国内云阿里云--京东云等推荐)**，不要执行 §6.1 中的 `get.docker.com`。

### 6.1 通用（海外或网络畅通）

```bash
sudo apt update && sudo apt upgrade -y

curl -fsSL https://get.docker.com | sh
sudo systemctl enable docker && sudo systemctl start docker
sudo usermod -aG docker $USER && newgrp docker

sudo apt install docker-compose-plugin -y
docker compose version
```

### 6.2 国内云（阿里云 / 京东云等，推荐）

官方 `get.docker.com` / `download.docker.com` 在国内常出现 `Connection reset by peer`，**改用阿里云 Docker CE 源**：

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl gnupg

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker compose version
```

**备选（一键脚本，仍走国内镜像）：**

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
sudo systemctl enable --now docker
docker compose version
```

**拉取镜像加速**（安装后建议配置，否则 `docker pull` / `compose up` 仍可能很慢）：

1. 阿里云控制台 → **容器镜像服务 ACR** → **镜像工具** → **镜像加速器**，复制专属地址。
2. 写入 `/etc/docker/daemon.json` 并重启：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json << 'EOF'
{
  "registry-mirrors": [
    "https://<你的阿里云镜像加速地址>"
  ]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
docker info | grep -A2 "Registry Mirrors"
```

无专属加速地址时，可临时使用公共源（稳定性因地区而异）：

```json
sudo tee /etc/docker/daemon.json << 'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io"
  ]
}
EOF
```

验证：`docker run --rm hello-world`

### 6.3 内核参数与目录（各场景通用）

```bash
sudo mkdir -p /opt/realman

cat >> /etc/sysctl.conf << 'EOF'
vm.overcommit_memory=1
net.core.somaxconn=65535
net.ipv4.tcp_max_syn_backlog=65535
fs.file-max=1000000
EOF
sudo sysctl -p

cat >> /etc/security/limits.conf << 'EOF'
* soft nofile 1000000
* hard nofile 1000000
EOF
```

**离线环境：** 提前 `docker save` 镜像，现场 `docker load`。

### 6.4 首次启动前：端口检查与数据目录权限

**6.4.1 端口占用检查（必做）**

应用默认占用 **80、8080、8085、1883**。若 8080 已被占用，`realman-system` 无法启动。

```bash
# 查看监听端口
ss -tlnp | grep -E ':80 |:8080|:8085|:1883'

# 查看是否有其他 Docker 容器占用（常见：历史 zlmediakit / MediaServer 占 8080）
docker ps --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}'
```


| 现象                      | 处理                                                                |
| ----------------------- | ----------------------------------------------------------------- |
| 8080 被非本项目的容器占用         | `docker stop <容器名>` 并 `docker update --restart=no <容器名>`，或改冲突服务端口 |
| 80 被宿主机 Nginx/Apache 占用 | 停掉宿主机服务或改 compose 中 Nginx 映射端口                                    |


**6.4.2 持久化目录权限（必做，否则 Loki/EMQX/Grafana 反复 Restarting）**

在 **第一次** `docker compose up` 之前执行：

```bash
# EMQX 数据目录（容器内 uid 1000）
mkdir -p /opt/realman/emqx/{data,log}
chown -R 1000:1000 /opt/realman/emqx/data /opt/realman/emqx/log
# 若 chown 1000 不可用：chmod -R 777 /opt/realman/emqx/data /opt/realman/emqx/log

# Loki 数据目录（容器内 uid 10001）
mkdir -p /opt/realman/loki/data
chown -R 10001:10001 /opt/realman/loki/data

# Grafana 数据目录（容器内 uid 472）
mkdir -p /opt/realman/grafana/data
chown -R 472:472 /opt/realman/grafana/data
```

---

## 7. 目录结构

**场景 A/B（无 mysql 目录）：**

```bash
mkdir -p /opt/realman/{redis/{data,conf,logs},nacos/logs,emqx/{data,log},minio/data,xxljob/logs,system/{upload,logs},iot/logs,loki/{data,config},grafana/{data,provisioning/{datasources,dashboards}},backup,app/{sql/migrations,build/{realman-system/target,realman-iot/target},nginx,frontend/{gln_teleop,gln_admin}}}
```

**场景 C：** 额外 `mkdir -p /opt/realman/mysql/{data,conf,logs}`

```
/opt/realman/
├── app/
│   ├── docker-compose.yml      ← §9 二选一
│   ├── .env                    ← §8.7 或 §8.8
│   ├── nginx/nginx.conf        ← §8.9
│   ├── sql/migrations/
│   ├── build/realman-system/   ← Dockerfile + jar
│   ├── build/realman-iot/
│   └── frontend/gln_teleop/、gln_admin/
├── redis/conf/redis.conf       ← §8.3
├── loki/config/loki-config.yml ← §8.4
├── grafana/provisioning/       ← §8.5、§8.6
└── （场景 C）mysql/conf/my.cnf ← §8.2
```

---

## 8. 完整配置文件

### 8.1 必改项速查


| 文件                                               | 必改                                                                     |
| ------------------------------------------------ | ---------------------------------------------------------------------- |
| `.env`                                           | 数据库、Grafana                                                            |
| `redis.conf` + compose healthcheck + Nacos       | Redis 密码三处一致；**无密码时三处均不写 password**（见 §8.3 说明）                         |
| `docker-compose.yml`                             | MinIO/Grafana 默认 IP、Redis 密码                                           |
| `nginx.conf`                                     | `location /realman-boot`（**不是** `/realman-system`）；须含 `resolver`（§8.9） |
| Nacos `realman-system.yaml` / `realman-iot.yaml` | JDBC、Redis、discovery、EMQX API；**勿写 context-path**                      |


### 8.2 MySQL 配置（场景 C）

路径：`/opt/realman/mysql/conf/my.cnf`

```ini
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00
max_connections=500
innodb_buffer_pool_size=512M
innodb_log_file_size=256M
slow_query_log=ON
slow_query_log_file=/var/log/mysql/slow.log
long_query_time=2
[client]
default-character-set=utf8mb4
```

### 8.3 Redis 配置

路径：`/opt/realman/redis/conf/redis.conf`

```conf
bind 0.0.0.0
port 6379
requirepass <REDIS_PASSWORD>
maxmemory 4gb
maxmemory-policy allkeys-lru
appendonly yes
appendfsync everysec
no-appendfsync-on-rewrite yes
save 900 1
save 300 10
loglevel notice
```

> **Redis 密码三处一致：** `redis.conf` 的 `requirepass`、compose `healthcheck` 的 `-a`、Nacos 里 `spring.data.redis.password`。  
> **若现场 Redis 不设密码：** 删除 `requirepass` 行；healthcheck 改为 `["CMD", "redis-cli", "ping"]`；Nacos Redis 配置**省略** `password` 字段。

### 8.4 Loki 配置

路径：`/opt/realman/loki/config/loki-config.yml`

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki/data
  storage:
    filesystem:
      chunks_directory: /loki/data/chunks
      rules_directory: /loki/data/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2026-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  ingestion_rate_mb: 16
  ingestion_burst_size_mb: 32
  max_entries_limit_per_query: 10000
  retention_period: 720h

compactor:
  working_directory: /loki/data/compactor
  delete_request_store: filesystem
  retention_enabled: true
  retention_delete_delay: 2h

ruler:
  alertmanager_url: http://localhost:9093
```

### 8.5 Grafana 数据源

路径：`/opt/realman/grafana/provisioning/datasources/datasources.yml`

```yaml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://realman-loki:3100
    isDefault: true
    jsonData:
      maxLines: 1000
      derivedFields:
        - datasourceUid: zipkin-ds
          matcherRegex: 'traceId=(\w+)'
          name: TraceID
          url: '$${__value.raw}'
    editable: true

  - name: Zipkin
    type: zipkin
    uid: zipkin-ds
    access: proxy
    url: http://realman-zipkin:9411
    editable: true
```

### 8.6 Grafana Dashboard 目录

路径：`/opt/realman/grafana/provisioning/dashboards/dashboards.yml`

```yaml
apiVersion: 1

providers:
  - name: default
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

```bash
mkdir -p /opt/realman/grafana/data/dashboards
```

**Grafana `origin not allowed`：** `GF_SERVER_ROOT_URL` 带尾斜杠且与浏览器 Host 一致；`GF_SECURITY_CSRF_TRUSTED_ORIGINS` 只填主机名/IP，不带 `http://`；Nginx `/grafana/` 须传 `X-Forwarded-Host`（见 §8.9）。

### 8.7 环境变量 `.env`（场景 A/B：云 RDS）

路径：`/opt/realman/app/.env`

```bash
cat > /opt/realman/app/.env << 'EOF'
RDS_HOST=<DB_HOST>
RDS_PORT=3306
RDS_USER=<DB_USER>
RDS_PASSWORD=<DB_PASSWORD>

TRACE_SAMPLING=0.1
ZIPKIN_ENDPOINT=http://realman-zipkin:9411/api/v2/spans
LOKI_URL=http://realman-loki:3100/loki/api/v1/push

SPRING_PROFILES_ACTIVE=prod

GRAFANA_ROOT_URL=http://<SERVER_IP>/grafana/
GRAFANA_DOMAIN=<SERVER_IP>
GRAFANA_CSRF_TRUSTED_ORIGINS=<SERVER_IP>
EOF
chmod 600 /opt/realman/app/.env
```

### 8.8 环境变量 `.env`（场景 C：本机 MySQL）

```bash
cat > /opt/realman/app/.env << 'EOF'
TRACE_SAMPLING=0.1
ZIPKIN_ENDPOINT=http://realman-zipkin:9411/api/v2/spans
LOKI_URL=http://realman-loki:3100/loki/api/v1/push

SPRING_PROFILES_ACTIVE=prod

GRAFANA_ROOT_URL=http://<SERVER_IP>/grafana/
GRAFANA_DOMAIN=<SERVER_IP>
GRAFANA_CSRF_TRUSTED_ORIGINS=<SERVER_IP>
EOF
chmod 600 /opt/realman/app/.env
```

### 8.9 Nginx 配置

路径：`/opt/realman/app/nginx/nginx.conf`

```nginx
events {
    worker_connections 1024;
}

http {
    include      /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile     on;
    keepalive_timeout 65;

    # Docker 内置 DNS：避免 Nginx 启动时 upstream 容器尚未创建导致 host not found
    resolver 127.0.0.11 valid=30s ipv6=off;

    upstream device_whoami_upstream {
        server host.docker.internal:9091;
        keepalive 8;
    }

    client_max_body_size 100m;

    access_log /var/log/nginx/access.log;
    error_log  /var/log/nginx/error.log;

    server {
        listen 80;
        server_name _;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        location ^~ /realman-iot/ws/ {
            set $upstream_iot_ws realman-iot:8085;
            proxy_pass         http://$upstream_iot_ws;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_set_header   Upgrade           $http_upgrade;
            proxy_set_header   Connection        "upgrade";
            proxy_read_timeout 3600s;
        }

        location /realman-boot {
            set $upstream_system realman-system:8080;
            proxy_pass         http://$upstream_system;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_set_header   Upgrade           $http_upgrade;
            proxy_set_header   Connection        $http_connection;
            proxy_read_timeout 3600s;
        }

        location /realman-iot {
            set $upstream_iot realman-iot:8085;
            proxy_pass         http://$upstream_iot;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_set_header   Upgrade           $http_upgrade;
            proxy_set_header   Connection        $http_connection;
            proxy_read_timeout 3600s;
        }

        location ^~ /api/v1/device/whoami {
            proxy_pass http://device_whoami_upstream;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_connect_timeout 10s;
            proxy_read_timeout 60s;
        }

        location = /gln_teleop {
            return 301 /gln_teleop/;
        }
        location ^~ /gln_teleop/ {
            root /usr/share/nginx/html;
            index index.html;
            try_files $uri $uri/ /gln_teleop/index.html;
        }

        location = /gln_admin {
            return 301 /gln_admin/;
        }
        location ^~ /gln_admin/ {
            root /usr/share/nginx/html;
            index index.html;
            try_files $uri $uri/ /gln_admin/index.html;
        }

        location /nacos {
            proxy_pass       http://realman-nacos:8848;
            proxy_read_timeout 300s;
        }

        location /xxl-job-admin {
            proxy_pass http://realman-xxljob:8080;
        }

        location = /grafana {
            return 301 /grafana/;
        }
        location /grafana/api/live/ {
            proxy_pass         http://realman-grafana:3000;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Forwarded-Host  $host;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   Upgrade           $http_upgrade;
            proxy_set_header   Connection        $http_connection;
        }
        location /grafana/ {
            proxy_pass         http://realman-grafana:3000;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Forwarded-Host  $host;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        }

        location /zipkin {
            proxy_pass http://realman-zipkin:9411;
        }

        location /emqx/ {
            proxy_pass         http://realman-emqx:18083/;
            proxy_http_version 1.1;
            proxy_set_header   Upgrade    $http_upgrade;
            proxy_set_header   Connection $http_connection;
            proxy_redirect     http://realman-emqx:18083/ /emqx/;
            proxy_set_header   Accept-Encoding "";
            sub_filter_once      on;
            sub_filter           '</head>' '<base href="/emqx/"/></head>';
        }
        location = /emqx {
            return 301 /emqx/;
        }

        location /minio/ {
            proxy_pass         http://realman-minio:9001/;
            proxy_http_version 1.1;
            proxy_set_header   Upgrade    $http_upgrade;
            proxy_set_header   Connection $http_connection;
            proxy_redirect     http://realman-minio:9001/ /minio/;
        }
        location = /minio {
            return 301 /minio/;
        }
    }
}
```

**访问地址（将 `<SERVER_IP>` 替换为现场 IP）：**


| 路径         | URL                                |
| ---------- | ---------------------------------- |
| 管理后台       | `http://<SERVER_IP>/gln_admin/`    |
| 遥操作前端      | `http://<SERVER_IP>/gln_teleop/`   |
| System API | `http://<SERVER_IP>/realman-boot`  |
| IoT API    | `http://<SERVER_IP>/realman-iot`   |
| Nacos      | `http://<SERVER_IP>/nacos`         |
| Grafana    | `http://<SERVER_IP>/grafana/`      |
| Zipkin     | `http://<SERVER_IP>/zipkin`        |
| EMQX       | `http://<SERVER_IP>/emqx/`         |
| MinIO      | `http://<SERVER_IP>/minio/`        |
| XXL-Job    | `http://<SERVER_IP>/xxl-job-admin` |


**whoami 502 排查：** 宿主机 9091 须监听 `0.0.0.0`；compose 中 nginx 需 `extra_hosts: host.docker.internal:host-gateway`。

**Nginx `host not found in upstream "realman-iot"`：** 须使用上文 `resolver` + `set $upstream_`* 写法；或 **先** `docker compose up -d realman-system realman-iot`，**再** `docker compose up -d nginx`。

**路径说明：** 前端请求 `/realman-boot/...`，Nginx `proxy_pass` 到容器 `realman-system:8080`（服务名），Spring `context-path` 仍为 `/realman-boot`，三者配合使用，无需改前端。

---

## 9. Docker Compose

保存为 `/opt/realman/app/docker-compose.yml`，**按场景二选一**。

### 9.1 场景 A/B：云 RDS 版

> 无 `mysql` 服务；Nacos / XXL-Job 通过 `.env` 的 `RDS_`* 连接云数据库。

```yaml
services:

  nginx:
    image: nginx:1.25-alpine
    container_name: realman-nginx
    restart: always
    ports:
      - "80:80"
    volumes:
      - /opt/realman/app/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - /opt/realman/app/frontend/gln_teleop:/usr/share/nginx/html/gln_teleop:ro
      - /opt/realman/app/frontend/gln_admin:/usr/share/nginx/html/gln_admin:ro
    depends_on:
      - nacos
      - grafana
      - zipkin
      - emqx
      - minio
      - xxl-job-admin
    extra_hosts:
      - "host.docker.internal:host-gateway"
    networks:
      - realman-net

  redis:
    image: redis:7.2
    container_name: realman-redis
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - /opt/realman/redis/data:/data
      - /opt/realman/redis/conf/redis.conf:/etc/redis/redis.conf
    command: redis-server /etc/redis/redis.conf
    environment:
      TZ: Asia/Shanghai
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "<REDIS_PASSWORD>", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - realman-net

  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: realman-nacos
    restart: always
    environment:
      TZ: Asia/Shanghai
      MODE: standalone
      SPRING_DATASOURCE_PLATFORM: mysql
      MYSQL_SERVICE_HOST: ${RDS_HOST}
      MYSQL_SERVICE_PORT: ${RDS_PORT}
      MYSQL_SERVICE_DB_NAME: nacos
      MYSQL_SERVICE_USER: ${RDS_USER}
      MYSQL_SERVICE_PASSWORD: ${RDS_PASSWORD}
      JVM_XMS: 256m
      JVM_XMX: 512m
    ports:
      - "8848:8848"
      - "9848:9848"
    volumes:
      - /opt/realman/nacos/logs:/home/nacos/logs
    networks:
      - realman-net

  emqx:
    image: emqx:5.8
    container_name: realman-emqx
    restart: always
    environment:
      TZ: Asia/Shanghai
      EMQX_NODE__NAME: emqx@realman-emqx
    ports:
      - "1883:1883"
      - "8083:8083"
      - "8883:8883"
    volumes:
      - /opt/realman/emqx/data:/opt/emqx/data
      - /opt/realman/emqx/log:/opt/emqx/log
    networks:
      - realman-net

  minio:
    image: minio/minio:RELEASE.2024-01-16T16-07-38Z
    container_name: realman-minio
    restart: always
    environment:
      TZ: Asia/Shanghai
      MINIO_ROOT_USER: <MINIO_USER>
      MINIO_ROOT_PASSWORD: <MINIO_PASSWORD>
      MINIO_BROWSER_REDIRECT_URL: http://<SERVER_IP>/minio/
    ports:
      - "9001:9000"
    volumes:
      - /opt/realman/minio/data:/data
    command: server /data --console-address ":9001"
    networks:
      - realman-net

  xxl-job-admin:
    image: xuxueli/xxl-job-admin:2.4.0
    container_name: realman-xxljob
    restart: always
    environment:
      TZ: Asia/Shanghai
      PARAMS: >-
        --spring.datasource.url=jdbc:mysql://${RDS_HOST}:${RDS_PORT}/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai
        --spring.datasource.username=${RDS_USER}
        --spring.datasource.password=${RDS_PASSWORD}
        --xxl.job.accessToken=
    ports:
      - "9080:8080"
    volumes:
      - /opt/realman/xxljob/logs:/data/applogs
    networks:
      - realman-net

  zipkin:
    image: openzipkin/zipkin:3
    container_name: realman-zipkin
    restart: always
    environment:
      TZ: Asia/Shanghai
      ZIPKIN_UI_BASEPATH: /zipkin
    networks:
      - realman-net

  loki:
    image: grafana/loki:3.0.0
    container_name: realman-loki
    restart: always
    ports:
      - "3100:3100"
    volumes:
      - /opt/realman/loki/config/loki-config.yml:/etc/loki/config.yml
      - /opt/realman/loki/data:/loki/data
    command: -config.file=/etc/loki/config.yml
    environment:
      TZ: Asia/Shanghai
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:3100/ready || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - realman-net

  grafana:
    image: grafana/grafana:11.0.0
    container_name: realman-grafana
    restart: always
    depends_on:
      loki:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_SERVER_ROOT_URL: ${GRAFANA_ROOT_URL:-http://<SERVER_IP>/grafana/}
      GF_SERVER_DOMAIN: ${GRAFANA_DOMAIN:-<SERVER_IP>}
      GF_SERVER_SERVE_FROM_SUB_PATH: "true"
      GF_SECURITY_CSRF_TRUSTED_ORIGINS: ${GRAFANA_CSRF_TRUSTED_ORIGINS:-<SERVER_IP>}
      GF_SECURITY_CSRF_ADDITIONAL_HEADERS: "X-Forwarded-Host X-Original-Host"
      GF_LOG_LEVEL: warn
      GF_AUTH_ANONYMOUS_ENABLED: "false"
    volumes:
      - /opt/realman/grafana/data:/var/lib/grafana
      - /opt/realman/grafana/provisioning:/etc/grafana/provisioning
      - /opt/realman/grafana/data/dashboards:/var/lib/grafana/dashboards
    networks:
      - realman-net

  realman-system:
    image: realman/realman-system:latest
    container_name: realman-system
    restart: on-failure
    depends_on:
      redis:
        condition: service_healthy
      nacos:
        condition: service_started
      loki:
        condition: service_healthy
    env_file:
      - .env
    environment:
      TZ: Asia/Shanghai
    ports:
      - "8080:8080"
    volumes:
      - /opt/realman/system/upload:/opt/upFiles
      - /opt/realman/system/logs:/app/logs
    networks:
      - realman-net

  realman-iot:
    image: realman/realman-iot:latest
    container_name: realman-iot
    restart: on-failure
    depends_on:
      redis:
        condition: service_healthy
      nacos:
        condition: service_started
      emqx:
        condition: service_started
      minio:
        condition: service_started
      loki:
        condition: service_healthy
    env_file:
      - .env
    environment:
      TZ: Asia/Shanghai
    ports:
      - "8085:8085"
    volumes:
      - /opt/realman/iot/logs:/app/logs
    networks:
      - realman-net

networks:
  realman-net:
    driver: bridge
    name: realman-net
```

### 9.2 场景 C：本机 MySQL 版

> 含 `mysql` 服务；Nacos / XXL-Job / 应用 `depends_on: mysql`。与 §9.1 相比：**新增 mysql**，nacos/xxl-job 环境变量改连 `realman-mysql`，应用 depends_on 增加 mysql。

```yaml
services:

  nginx:
    image: nginx:1.25-alpine
    container_name: realman-nginx
    restart: always
    ports:
      - "80:80"
    volumes:
      - /opt/realman/app/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - /opt/realman/app/frontend/gln_teleop:/usr/share/nginx/html/gln_teleop:ro
      - /opt/realman/app/frontend/gln_admin:/usr/share/nginx/html/gln_admin:ro
    depends_on:
      - nacos
      - grafana
      - zipkin
      - emqx
      - minio
      - xxl-job-admin
    extra_hosts:
      - "host.docker.internal:host-gateway"
    networks:
      - realman-net

  mysql:
    image: mysql:8.0
    container_name: realman-mysql
    restart: always
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: <MYSQL_ROOT_PASSWORD>
      TZ: Asia/Shanghai
    volumes:
      - /opt/realman/mysql/data:/var/lib/mysql
      - /opt/realman/mysql/conf/my.cnf:/etc/mysql/conf.d/my.cnf
      - /opt/realman/mysql/logs:/var/log/mysql
    command:
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --default-time-zone=+08:00
      --max_connections=500
      --innodb-buffer-pool-size=512M
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-p<MYSQL_ROOT_PASSWORD>"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
    networks:
      - realman-net

  redis:
    image: redis:7.2
    container_name: realman-redis
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - /opt/realman/redis/data:/data
      - /opt/realman/redis/conf/redis.conf:/etc/redis/redis.conf
    command: redis-server /etc/redis/redis.conf
    environment:
      TZ: Asia/Shanghai
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "<REDIS_PASSWORD>", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - realman-net

  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: realman-nacos
    restart: always
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      MODE: standalone
      SPRING_DATASOURCE_PLATFORM: mysql
      MYSQL_SERVICE_HOST: realman-mysql
      MYSQL_SERVICE_PORT: 3306
      MYSQL_SERVICE_DB_NAME: nacos
      MYSQL_SERVICE_USER: root
      MYSQL_SERVICE_PASSWORD: <MYSQL_ROOT_PASSWORD>
      JVM_XMS: 256m
      JVM_XMX: 512m
    ports:
      - "8848:8848"
      - "9848:9848"
    volumes:
      - /opt/realman/nacos/logs:/home/nacos/logs
    networks:
      - realman-net

  emqx:
    image: emqx:5.8
    container_name: realman-emqx
    restart: always
    environment:
      TZ: Asia/Shanghai
      EMQX_NODE__NAME: emqx@realman-emqx
    ports:
      - "1883:1883"
      - "8083:8083"
      - "8883:8883"
    volumes:
      - /opt/realman/emqx/data:/opt/emqx/data
      - /opt/realman/emqx/log:/opt/emqx/log
    networks:
      - realman-net

  minio:
    image: minio/minio:RELEASE.2024-01-16T16-07-38Z
    container_name: realman-minio
    restart: always
    environment:
      TZ: Asia/Shanghai
      MINIO_ROOT_USER: <MINIO_USER>
      MINIO_ROOT_PASSWORD: <MINIO_PASSWORD>
      MINIO_BROWSER_REDIRECT_URL: http://<SERVER_IP>/minio/
    ports:
      - "9001:9000"
    volumes:
      - /opt/realman/minio/data:/data
    command: server /data --console-address ":9001"
    networks:
      - realman-net

  xxl-job-admin:
    image: xuxueli/xxl-job-admin:2.4.0
    container_name: realman-xxljob
    restart: always
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      PARAMS: >-
        --spring.datasource.url=jdbc:mysql://realman-mysql:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai
        --spring.datasource.username=root
        --spring.datasource.password=<MYSQL_ROOT_PASSWORD>
        --xxl.job.accessToken=
    ports:
      - "9080:8080"
    volumes:
      - /opt/realman/xxljob/logs:/data/applogs
    networks:
      - realman-net

  zipkin:
    image: openzipkin/zipkin:3
    container_name: realman-zipkin
    restart: always
    environment:
      TZ: Asia/Shanghai
      ZIPKIN_UI_BASEPATH: /zipkin
    networks:
      - realman-net

  loki:
    image: grafana/loki:3.0.0
    container_name: realman-loki
    restart: always
    ports:
      - "3100:3100"
    volumes:
      - /opt/realman/loki/config/loki-config.yml:/etc/loki/config.yml
      - /opt/realman/loki/data:/loki/data
    command: -config.file=/etc/loki/config.yml
    environment:
      TZ: Asia/Shanghai
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:3100/ready || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - realman-net

  grafana:
    image: grafana/grafana:11.0.0
    container_name: realman-grafana
    restart: always
    depends_on:
      loki:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_SERVER_ROOT_URL: ${GRAFANA_ROOT_URL:-http://<SERVER_IP>/grafana/}
      GF_SERVER_DOMAIN: ${GRAFANA_DOMAIN:-<SERVER_IP>}
      GF_SERVER_SERVE_FROM_SUB_PATH: "true"
      GF_SECURITY_CSRF_TRUSTED_ORIGINS: ${GRAFANA_CSRF_TRUSTED_ORIGINS:-<SERVER_IP>}
      GF_SECURITY_CSRF_ADDITIONAL_HEADERS: "X-Forwarded-Host X-Original-Host"
      GF_LOG_LEVEL: warn
      GF_AUTH_ANONYMOUS_ENABLED: "false"
    volumes:
      - /opt/realman/grafana/data:/var/lib/grafana
      - /opt/realman/grafana/provisioning:/etc/grafana/provisioning
      - /opt/realman/grafana/data/dashboards:/var/lib/grafana/dashboards
    networks:
      - realman-net

  realman-system:
    image: realman/realman-system:latest
    container_name: realman-system
    restart: on-failure
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      nacos:
        condition: service_started
      loki:
        condition: service_healthy
    env_file:
      - .env
    environment:
      TZ: Asia/Shanghai
    ports:
      - "8080:8080"
    volumes:
      - /opt/realman/system/upload:/opt/upFiles
      - /opt/realman/system/logs:/app/logs
    networks:
      - realman-net

  realman-iot:
    image: realman/realman-iot:latest
    container_name: realman-iot
    restart: on-failure
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      nacos:
        condition: service_started
      emqx:
        condition: service_started
      minio:
        condition: service_started
      loki:
        condition: service_healthy
    env_file:
      - .env
    environment:
      TZ: Asia/Shanghai
    ports:
      - "8085:8085"
    volumes:
      - /opt/realman/iot/logs:/app/logs
    networks:
      - realman-net

networks:
  realman-net:
    driver: bridge
    name: realman-net
```

**gln_teleop / gln_admin 404：** 确认 frontend 目录已挂载且含 `index.html`；前端构建 `base` 须为 `/gln_teleop/`、`/gln_admin/`。

---

## 10. Dockerfile

### 10.1 realman-system

路径：`/opt/realman/app/build/realman-system/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="realman@realman-robot.com"

WORKDIR /app

ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    rm -rf /var/cache/apk/*

COPY target/realman-system-start-*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=70.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Xlog:gc*:file=/app/logs/gc.log:time,uptime:filecount=5,filesize=20m \
    -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 10.2 realman-iot

路径：`/opt/realman/app/build/realman-iot/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="realman@realman-robot.com"

WORKDIR /app

ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    rm -rf /var/cache/apk/*

COPY target/realman-boot-iot-start-*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=70.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Xlog:gc*:file=/app/logs/gc.log:time,uptime:filecount=5,filesize=20m \
    -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8085

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

---

## 11. Nacos 生产配置

登录 `http://<SERVER_IP>/nacos`（默认 `nacos` / `nacos`，**上线后立即改密**）。  
命名空间：`public`，Group：`REALMAN_GROUP`。

### 11.0 配置规则（必读）


| 规则     | 说明                                                                |
| ------ | ----------------------------------------------------------------- |
| DataId | `realman-system.yaml`、`realman-iot.yaml`                          |
| **禁止** | `server.servlet.context-path`（保持 jar 内 `/realman-boot`）           |
| **禁止** | 拷贝公司测试环境密码到客户 Nacos                                               |
| 启动验证   | 日志含 `Load config[dataId=realman-system.yaml` / `realman-iot.yaml` |


应用 jar 已通过 `spring.config.import` 拉取上述 DataId，**无需**在 Nacos 再建 `realman-boot.yaml`。

### 11.1 realman-system.yaml（云 RDS）

DataId：`realman-system.yaml`

```yaml
spring:
  datasource:
    dynamic:
      datasource:
        master:
          url: jdbc:mysql://<DB_HOST>:3306/realman-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
          username: <DB_USER>
          password: <DB_PASSWORD>
  data:
    redis:
      host: realman-redis
      port: 6379
      password: <REDIS_PASSWORD>
      database: 0

jeecg:
  uploadType: local
  path:
    upload: /opt/upFiles
    webapp: /opt/webapp
  signatureSecret: <SIGNATURE_SECRET>
  firewall:
    lowCodeMode: prod
    dataSourceSafe: true
  redisson:
    address: realman-redis:6379
    password: <REDIS_PASSWORD>
    type: STANDALONE
    enabled: true
  xxljob:
    enabled: true
    adminAddresses: http://realman-xxljob:8080/xxl-job-admin
    appname: realman-boot

knife4j:
  production: true

server:
  error:
    include-stacktrace: NEVER
    include-exception: false
```

### 11.2 realman-iot.yaml（云 RDS）

DataId：`realman-iot.yaml`

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: realman-nacos:8848
        group: REALMAN_GROUP
        username: nacos
        password: nacos
  datasource:
    url: jdbc:mysql://<DB_HOST>:3306/realman-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: <DB_USER>
    password: <DB_PASSWORD>
  data:
    redis:
      host: realman-redis
      port: 6379
      password: <REDIS_PASSWORD>   # Redis 无密码时删除本行
      database: 0
      timeout: 3000
      lettuce:
        pool:
          max-wait: 2000

mqtt:
  enabled: true
  broker:
    url: tcp://realman-emqx:1883
    username: iot-platform
    password: <MQTT_PASSWORD>
    client-id: iot-platform-server
    qos: 1
    keep-alive: 60
  emqx:
    api-url: http://realman-emqx:18083
    api-username: admin              # 或 EMQX「API 密钥」的 Key ID
    api-password: <EMQX_API_PASSWORD> # Dashboard 改密后的密码，或 API Key Secret
    api-timeout-ms: 5000
    ensure-platform-superuser: true
    reconcile-enabled: true
    reconcile-lock-seconds: 120

minio:
  endpoint: http://realman-minio:9000
  access-key: <MINIO_USER>
  secret-key: <MINIO_PASSWORD>
  bucket-name:
    firmware: iot-firmware
    slam: iot-slam
  url-expire-days: 7

xxl:
  job:
    enabled: true
    admin:
      addresses: http://realman-xxljob:8080/xxl-job-admin
    executor:
      appname: realman-iot
      port: 30007
    accessToken:

webrtc:
  turn-server:
    username: "realman"
    password: "TBdWj0HhwTK56AzK"
  signaling:
    server:
      port: 8091
  turn-router:
    base-url: "http://turn-router:8081"
    connect-timeout-ms: 5000
    read-timeout-ms: 5000

# 未部署 Darwin 数采 / RocketMQ 时保持 false（现场默认）
darwin:
  integration:
    enabled: false

device:
  encrypt:
    enabled: true   # 生产建议 true；联调可设 false
```

> **Darwin 集成：** `darwin.integration.enabled=false` 时 IoT 可独立运行，不依赖 RocketMQ。启用 Darwin 须同时部署 RocketMQ 并设 `enabled: true`。  
> **Nacos Discovery：** IoT 启动时会向 Nacos 注册服务名 `realman-iot`；缺少 `spring.cloud.nacos.discovery` 会导致注册失败并退出。新版 jar 已内置，**仍建议在 Nacos 显式配置**便于运维核对。  
> **EMQX Management API：** `mqtt.emqx.api-`* 用于启动时设置平台 superuser、同步设备在线状态；401 不影响 MQTT 连接，但建议按 §12.1 配置。

### 11.3 realman-system.yaml（本机 MySQL）

与 §11.1 相同，仅 JDBC 改为：

```yaml
          url: jdbc:mysql://realman-mysql:3306/realman-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
          username: root
          password: <MYSQL_ROOT_PASSWORD>
```

### 11.4 realman-iot.yaml（本机 MySQL）

与 §11.2 相同，仅 JDBC 改为：

```yaml
    url: jdbc:mysql://realman-mysql:3306/realman-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: <MYSQL_ROOT_PASSWORD>
```

---

## 12. EMQX 初始化与设备 MQTT 鉴权

> **IoT 安全模型**
>
> - **连接层一机一密**：每台设备在库表 `iot_device.device_secret`，经 EMQX HTTP Auth 校验（§12.2）。
> - **消息体加密**：密钥由 `SHA256(deviceCode)` 派生，见 `CommandEncryptService`；由 Nacos `device.encrypt.enabled` 控制（§11.2）。无需 `.env` 主密钥。

### 12.1 首次部署（内置用户 + 平台账号 + Management API）

**EMQX 数据目录权限**（首次启动前，否则反复 Restarting）：见 [§6.4.2](#642-持久化目录权限必做否则-lokiemqxgrafana-反复-restarting)。

访问 `http://<SERVER_IP>/emqx/`（默认 `admin` / `public`）：

1. **立即修改 Dashboard 密码** → 记为 `<EMQX_API_PASSWORD>`，写入 Nacos `mqtt.emqx.api-password`（§11.2）。
2. （推荐）**系统 → API 密钥 → 创建**，将 Key ID / Secret 写入 Nacos `mqtt.emqx.api-username` / `api-password`。
3. `Access Control → Authentication → Built-in Database → 添加用户`：
  - 用户名：`iot-platform`
  - 密码：`<MQTT_PASSWORD>`（与 Nacos `mqtt.broker.password` 一致）

> **两套密码别混：** `iot-platform` 是 MQTT **连接**密码；Dashboard/API Key 是 **18083 管理接口**密码。

### 12.2 设备一机一密：EMQX HTTP 认证与 ACL

**Authentication（AuthN）**：`Management → AuthN` → 添加 **HTTP Server**

- URL：`http://realman-iot:8085/realman-iot/internal/mqtt/auth`
- Body：

```json
{
  "clientid": "${clientid}",
  "username": "${username}",
  "password": "${password}",
  "peerhost": "${peerhost}"
}
```

**Authorization（AuthZ）**：`Management → AuthZ` → 添加 **HTTP Server**

- URL：`http://realman-iot:8085/realman-iot/internal/mqtt/acl`
- Body：

```json
{
  "clientid": "${clientid}",
  "username": "${username}",
  "topic": "${topic}",
  "action": "${action}"
}
```

### 12.3 MinIO 初始化

```bash
docker run --rm --network realman-net --entrypoint /bin/sh minio/mc:latest -c "
  mc alias set local http://realman-minio:9000 <MINIO_USER> <MINIO_PASSWORD> &&
  mc mb --ignore-existing local/iot-firmware &&
  mc mb --ignore-existing local/iot-slam &&
  mc ls local
"
```

### 12.4 控制台默认账号（首次登录后必改）


| 控制台     | 地址               | 默认账号                     | 说明                      |
| ------- | ---------------- | ------------------------ | ----------------------- |
| Nacos   | `/nacos`         | nacos / nacos            | 改密后同步 `.env` 若应用用鉴权     |
| Grafana | `/grafana/`      | admin / admin            |                         |
| EMQX    | `/emqx/`         | admin / public           | §12.1 另建 `iot-platform` |
| XXL-Job | `/xxl-job-admin` | admin / 123456           |                         |
| MinIO   | `/minio/`        | 同 compose `MINIO_ROOT_*` |                         |


---

## 13. 应用构建与镜像打包

**公司侧：**

```bash
GIT_SHA=$(git rev-parse --short HEAD)

mvn clean package -pl realman-boot-system/realman-system-start -am -DskipTests -Pprod
mvn clean package -pl realman-boot-iot/realman-boot-iot-start -am -DskipTests -Pprod

SERVER="root@<SERVER_IP>"
scp realman-boot-system/realman-system-start/Dockerfile ${SERVER}:/opt/realman/app/build/realman-system/
scp realman-boot-system/realman-system-start/target/realman-system-start-*.jar ${SERVER}:/opt/realman/app/build/realman-system/target/
scp realman-boot-iot/realman-boot-iot-start/Dockerfile ${SERVER}:/opt/realman/app/build/realman-iot/
scp realman-boot-iot/realman-boot-iot-start/target/realman-boot-iot-start-*.jar ${SERVER}:/opt/realman/app/build/realman-iot/target/
```

**客户机侧：**

```bash
cd /opt/realman/app
GIT_SHA="<版本号>"

# 须在各自 Dockerfile 所在目录构建（不要直接在 app/ 下 docker build .）
docker build -t realman/realman-system:${GIT_SHA} -t realman/realman-system:latest build/realman-system/
docker build -t realman/realman-iot:${GIT_SHA} -t realman/realman-iot:latest build/realman-iot/

docker images | grep realman
```

> **常见错误：** 在 `/opt/realman/app` 执行 `docker build .` 会找不到 Dockerfile/jar。正确路径为 `build/realman-system/`、`build/realman-iot/`。

---

## 14. 标准部署流程（分阶段）

> **用法：** 首次部署从阶段 1 顺序执行到阶段 8；更新版本时跳过阶段 1～2，从阶段 3 或阶段 6 起。  
> 可打印 [附录 A](#附录-a首次部署勾检表云服务器可打印) 边做边勾选。

---

### 阶段 1：服务器与 Docker（§6）


| 步骤  | 操作                                                        |
| --- | --------------------------------------------------------- |
| 1.1 | 国内云 ECS 执行 [§6.2](#62-国内云阿里云--京东云等推荐) 安装 Docker + Compose |
| 1.2 | 配置镜像加速（§6.2 拉取镜像加速）                                       |
| 1.3 | 执行 [§6.3](#63-内核参数与目录各场景通用) 内核参数                          |
| 1.4 | 执行 [§6.4](#64-首次启动前端口检查与数据目录权限) 端口检查 + 目录权限               |
| 1.5 | 创建 [§7](#7-目录结构) 全部目录                                     |


**通过标准：** `docker compose version` 正常；`ss -tlnp` 无 8080/80 冲突；EMQX/Loki/Grafana 目录权限已设置。

---

### 阶段 2：数据库（§4 或 §5）

**场景 A/B（云 RDS）：**


| 步骤  | 操作                                                        |
| --- | --------------------------------------------------------- |
| 2.1 | RDS 控制台建库 `realman-boot`、`nacos`、`xxl_job`；白名单加 ECS 内网 IP |
| 2.2 | 上传四份 SQL 到 `/opt/realman/app/sql/`                        |
| 2.3 | 写入 [§8.7](#87-环境变量-env场景-ab云-rds) `.env`                  |
| 2.4 | 执行 [§4.3](#43-连通性验证必做) 连通性验证                              |
| 2.5 | 执行 [§4.4](#44-云-rds-首次初始化-sql必做) 导入初始化 SQL                |


**场景 C（本机 MySQL）：** 按 [§5](#5-本机-mysql-准备场景-c) 启动 mysql 并导入 SQL；使用 [§8.8](#88-环境变量-env场景-c本机-mysql) `.env`。

**通过标准：** `mysqld is alive`；`SHOW DATABASES` 含三个库；业务表已存在（如 `sys_user`）。

---

### 阶段 3：写入配置文件（§8、§9）


| 步骤  | 操作                                                                              |
| --- | ------------------------------------------------------------------------------- |
| 3.1 | 写入 `redis.conf`（§8.3）、`loki-config.yml`（§8.4）                                   |
| 3.2 | 写入 Grafana provisioning（§8.5、§8.6）                                              |
| 3.3 | 写入 `nginx.conf`（§8.9，**含 resolver**）                                            |
| 3.4 | 复制 [§9.1](#91-场景-ab云-rds-版) 或 [§9.2](#92-场景-c本机-mysql-版) 为 `docker-compose.yml` |
| 3.5 | **全局替换** compose / redis / nginx / `.env` 中全部 `<占位符>`                           |
| 3.6 | 上传前端到 `frontend/gln_admin`、`frontend/gln_teleop`（如有）                            |


**通过标准：** `grep -r '<DB_HOST>\|<SERVER_IP>\|<REDIS_PASSWORD>' /opt/realman/app/` 无残留占位符（除注释外）。

---

### 阶段 4：启动中间件

```bash
cd /opt/realman/app

# 场景 C 先启 MySQL：
# docker compose up -d mysql
# until docker inspect realman-mysql --format='{{.State.Health.Status}}' | grep -q healthy; do sleep 3; done

# 4.1 Redis
docker compose up -d redis
until docker inspect realman-redis --format='{{.State.Health.Status}}' | grep -q healthy; do
  sleep 3; echo "等待 Redis..."
done

# 4.2 Nacos（依赖 RDS 中 nacos 库已初始化）
docker compose up -d nacos && sleep 25
curl -sf http://localhost:8848/nacos/v1/console/health/liveness && echo " Nacos OK"

# 4.3 EMQX / MinIO / XXL-Job
docker compose up -d emqx minio xxl-job-admin && sleep 15

# 4.4 可观测性
docker compose up -d zipkin loki
until curl -sf http://localhost:3100/ready; do sleep 5; echo "等待 Loki..."; done
docker compose up -d grafana && sleep 10
```

**通过标准：**

```bash
docker compose ps   # redis/nacos/emqx/minio/loki/grafana 均为 Up（非 Restarting）
curl -sf http://localhost:8848/nacos/     # Nacos 控制台可访问（:8848 或后续经 Nginx :80/nacos）
curl -sf http://localhost:3100/ready      # Loki OK
```


| 若 Restarting | 处理                                                                          |
| ------------ | --------------------------------------------------------------------------- |
| Loki         | `chown -R 10001:10001 /opt/realman/loki/data` → restart                     |
| Grafana      | `chown -R 472:472 /opt/realman/grafana/data` → restart                      |
| EMQX         | `chown -R 1000:1000 /opt/realman/emqx/data /opt/realman/emqx/log` → restart |
| Nacos        | 检查 RDS 连接、是否已导入 `nacos_init.sql`                                            |


---

### 阶段 5：控制台初始化（Nacos + EMQX + MinIO）

**5.1 Nacos** — 登录 `http://<SERVER_IP>:8848/nacos` 或中间件阶段暂用 `:8848`（Nginx 未启前）

- 命名空间：`public`（默认）
- 新建配置，Group：`REALMAN_GROUP`
- DataId + 内容：[§11.1](#111-realman-systemyaml云-rds) `realman-system.yaml`、[§11.2](#112-realman-iotyaml云-rds) `realman-iot.yaml`
- **检查：** 密码、RDS 内网地址、`darwin.integration.enabled: false`、IoT 含 `discovery` 与 `mqtt.emqx.api-`*

**5.2 EMQX** — [§12.1](#121-首次部署内置用户--平台账号--management-api)～[§12.2](#122-设备一机一密emqx-http-认证与-acl)

> **须等阶段 6 中 IoT 启动后再配 §12.2 HTTP Auth/ACL**（URL 指向 `realman-iot` 容器）。

**5.3 MinIO** — [§12.3](#123-minio-初始化) 创建 `iot-firmware`、`iot-slam` 桶

**通过标准：** Nacos 配置列表可见两个 DataId；EMQX 已建 `iot-platform` 用户；MinIO 两桶存在。

---

### 阶段 6：构建并加载应用镜像（§13）

**公司侧打包：**

```bash
mvn clean package -pl realman-boot-system/realman-system-start -am -DskipTests -Pprod
mvn clean package -pl realman-boot-iot/realman-boot-iot-start -am -DskipTests -Pprod
# scp Dockerfile + jar 到 ECS build/realman-system/target、build/realman-iot/target
```

**ECS 构建：**

```bash
cd /opt/realman/app
docker build -t realman/realman-system:latest build/realman-system/
docker build -t realman/realman-iot:latest build/realman-iot/
```

**通过标准：** `docker images | grep realman` 可见 latest 镜像。

---

### 阶段 7：启动应用与 Nginx

```bash
cd /opt/realman/app

docker compose up -d realman-system && sleep 35
docker logs realman-system --tail 80 | grep -E "Started|Load config|ERROR"

docker compose up -d realman-iot && sleep 35
docker logs realman-iot --tail 80 | grep -E "Started|Load config|ERROR|register"

docker compose up -d nginx
docker logs realman-nginx --tail 30
```

**通过标准（日志关键字）：**


| 应用             | 必须出现                                                                                                             | 不得出现（启动失败）                                                     |
| -------------- | ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| realman-system | `Started RealmanSystemApplication`、`Load config[dataId=realman-system.yaml]`                                     | `APPLICATION FAILED TO START`                                  |
| realman-iot    | `Started RealmanDeviceApplication`、`nacos registry ... register finished`、`Load config[dataId=realman-iot.yaml]` | `OssAddressReportHandler`、`Client not connected`（discovery 缺失） |


**IoT 启动后补做：** EMQX HTTP Auth/ACL（§12.2）。

---

### 阶段 8：全量验收（§15、§19）

```bash
# 健康检查
curl -s http://<SERVER_IP>/realman-boot/actuator/health | grep '"status":"UP"'
curl -s http://<SERVER_IP>/realman-iot/actuator/health   | grep '"status":"UP"'

# 控制台
# Nacos 服务列表 → realman-boot、realman-iot 实例健康
# Grafana → Loki 有 realman-iot 日志
# EMQX → 连接数、iot-platform 在线
```

填写 [§19 客户交付清单](#19-客户交付清单)，密码单独加密交付。

---

### 14.x 更新部署（非首次）


| 步骤         | 说明                                                |
| ---------- | ------------------------------------------------- |
| 备份         | [§14.3 旧版备份](#143-更新部署备份首次跳过)                     |
| DDL        | 若有迁移 SQL，[§14.5](#145-ddl更新部署)                    |
| 换 jar / 镜像 | §13 重新 build                                      |
| 滚动         | `docker compose up -d realman-system realman-iot` |
| 验证         | §15                                               |


### 14.3 更新部署：备份（首次跳过）

**云 RDS：**

```bash
source /opt/realman/app/.env
BACKUP_FILE="/opt/realman/backup/realman-boot_$(date +%Y%m%d_%H%M%S).sql.gz"
docker run --rm mysql:8.0 \
  mysqldump -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} \
  --single-transaction --quick realman-boot | gzip > ${BACKUP_FILE}
```

**本机 MySQL：**

```bash
docker exec realman-mysql mysqldump -uroot -p'<MYSQL_ROOT_PASSWORD>' \
  --single-transaction --quick realman-boot | gzip > /opt/realman/backup/realman-boot_$(date +%Y%m%d_%H%M%S).sql.gz
```

### 14.5 DDL（更新部署）

```bash
source /opt/realman/app/.env   # 场景 A/B

# 云 RDS：
docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} \
  realman-boot < /sql/migrations/v<VERSION>_migrate.sql

# 本机 MySQL：
# docker exec -i realman-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' realman-boot \
#   < ./sql/migrations/v<VERSION>_migrate.sql
```

> **说明：** 阶段 4～7 的详细命令已合并进上文分阶段流程；以下为历史兼容锚点，首次部署请直接按阶段 1～8 执行。



### 14.1 写入全部配置

见 **阶段 3**。

### 14.2 验证数据库

见 **阶段 2**。

### 14.4 启动基础层

见 **阶段 4** 中 Redis 部分。

### 14.6 中间件 → 可观测性

见 **阶段 4**。

### 14.7 首次：EMQX + MinIO + Nacos

见 **阶段 5**。

### 14.8 应用 + Nginx

见 **阶段 6、阶段 7**。

---

## 15. 部署验证

### 15.1 中间件

```bash
source /opt/realman/app/.env   # 场景 A/B

# 数据库（A/B）
docker run --rm mysql:8.0 mysqladmin -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} ping
# 数据库（C）
# docker exec realman-mysql mysqladmin -uroot -p'<MYSQL_ROOT_PASSWORD>' ping

docker exec realman-redis redis-cli -a '<REDIS_PASSWORD>' ping
curl -sf http://localhost:8848/nacos/v1/console/health/liveness && echo " Nacos OK"
curl -sf http://localhost:3100/ready && echo " Loki OK"
curl -sf http://localhost:9001/minio/health/live && echo " MinIO OK"
curl -sf http://localhost:9411/health && echo " Zipkin OK"
curl -sf http://localhost:3000/api/health
```

### 15.2 应用

```bash
curl -s http://localhost:8080/realman-boot/actuator/health | grep '"status"'
curl -s http://localhost:8085/realman-iot/actuator/health   | grep '"status"'
```

经 Nginx（与前端一致）：

```bash
curl -s http://<SERVER_IP>/realman-boot/actuator/health | grep '"status"'
curl -s http://<SERVER_IP>/realman-iot/actuator/health   | grep '"status"'
```

### 15.3 冒烟

```bash
curl -s -X POST http://<SERVER_IP>/realman-boot/sys/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<初始密码>","captcha":"","checkKey":""}'

mosquitto_pub -h <SERVER_IP> -p 1883 -u iot-platform -P '<MQTT_PASSWORD>' \
  -t "device/test/heartbeat" -m '{"deviceId":"test-001","status":"online"}' -d
```

### 15.4 Grafana / Loki

Explore → Loki → 生产环境用 `{app="realman-iot"}`（开发/测试环境可能是 `realman-iot-szy`）；含 `traceId` 日志可跳转 Zipkin。

### 15.5 Loki 命令行验证

```bash
curl -G "http://localhost:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={app="realman-iot"}' \
  --data-urlencode "start=$(date -d '5 minutes ago' +%s)000000000" \
  --data-urlencode "end=$(date +%s)000000000" \
  --data-urlencode 'limit=10'
```

---

## 16. 回滚

### 16.1 应用

```bash
ROLLBACK_SHA="<上一版本>"
docker compose stop realman-iot realman-system

docker run -d --name realman-system-rb --network realman-net \
  --env-file /opt/realman/app/.env -e TZ=Asia/Shanghai -p 8080:8080 \
  -v /opt/realman/system/upload:/opt/upFiles \
  -v /opt/realman/system/logs:/app/logs \
  realman/realman-system:${ROLLBACK_SHA}

docker run -d --name realman-iot-rb --network realman-net \
  --env-file /opt/realman/app/.env -e TZ=Asia/Shanghai -p 8085:8085 \
  -v /opt/realman/iot/logs:/app/logs \
  realman/realman-iot:${ROLLBACK_SHA}
```

### 16.2 数据库

```bash
source /opt/realman/app/.env
docker run --rm -v /opt/realman/app/sql:/sql mysql:8.0 \
  mysql -h ${RDS_HOST} -P ${RDS_PORT} -u${RDS_USER} -p${RDS_PASSWORD} \
  realman-boot < /sql/migrations/v<VERSION>_rollback.sql
```

### 16.3 Nacos

控制台 → 配置历史 → 回滚 → `docker compose restart realman-system realman-iot`

---

## 17. 现场故障排查

### 17.1 速查表


| 现象                                                              | 原因                                                 | 处理                                                            |
| --------------------------------------------------------------- | -------------------------------------------------- | ------------------------------------------------------------- |
| RDS 连接超时                                                        | 公网地址 / 白名单 / 不同 VPC                                | §4.3                                                          |
| Nacos 起不来                                                       | 未导入 `nacos_init.sql` 或 JDBC 错                      | §4.4、核对 `.env`                                                |
| Redis 认证失败                                                      | 密码三处不一致                                            | redis.conf / compose healthcheck / Nacos                      |
| 无 `Load config` 日志                                              | DataId 或 Group 错误                                  | 须为 `realman-system.yaml`、`realman-iot.yaml` + `REALMAN_GROUP` |
| `/realman-boot` 404                                             | Nginx 写成 `/realman-system` 或 Nacos 改了 context-path | §1.1、§8.9                                                     |
| Nginx Restarting                                                | `host not found in upstream "realman-iot"`         | §8.9 resolver；或先启应用再启 Nginx                                   |
| 8080 端口冲突                                                       | 宿主机或其他容器占用                                         | §6.4.1                                                        |
| Loki/Grafana/EMQX Restarting                                    | 数据目录权限                                             | §6.4.2                                                        |
| IoT：`OssAddressReportHandler` bean not found                    | 旧 jar + `darwin.integration.enabled=false`         | 使用含 Darwin 可选依赖修复的新版 jar 重打镜像                                 |
| IoT：`NacosException Client not connected` / `serverAddr='null'` | 缺少 Nacos Discovery 配置                              | §11.2 `spring.cloud.nacos.discovery`                          |
| IoT：`EmqxApi 401 Unauthorized`                                  | 未配 `mqtt.emqx.api-username/password`               | §12.1、§11.2                                                   |
| IoT：`RocketMQClientTemplate` 缺失                                 | Darwin 开启但未部署 RocketMQ                             | Nacos 设 `darwin.integration.enabled: false`                   |
| MQTT 设备连不上                                                      | 1883 未开 / 未配 HTTP Auth                             | §3.2、§12.2                                                    |
| 前端 404                                                          | 未挂 frontend 或 base 路径错                             | compose volumes；前端 base=`/gln_admin/`                         |
| Grafana origin 错误                                               | root_url / CSRF / Nginx Host                       | §8.6、§8.9                                                     |
| 磁盘满                                                             | Loki / MinIO / 日志                                  | `du -sh /opt/realman/*`                                       |


### 17.2 可忽略的日志（应用已 Started 时）


| 日志                                               | 说明                           |
| ------------------------------------------------ | ---------------------------- |
| Nacos client logback `CONFIG_LOG_FILE collision` | Nacos SDK 日志冲突，不影响业务         |
| `minidao.base-package is not set`                | IoT 未使用 MiniDao              |
| `BeanPostProcessorChecker ... not eligible`      | Spring Cloud / Shiro 常见 WARN |
| `xxl-job accessToken is empty`                   | 建议生产配置 token，不阻塞启动           |
| `未找到机器人 xxx 主控缓存`                                | 设备在上报但库中无绑定，业务数据问题           |
| `[QqWry] 未配置 qqwry.path`                         | IP 归属地可选功能                   |


### 17.3 常用诊断命令

```bash
cd /opt/realman/app
docker compose ps
docker logs realman-system --tail 200
docker logs realman-iot --tail 200
docker logs realman-nginx --tail 50
ss -tlnp | grep -E ':80|:8080|:8085|:1883'
df -h /opt && docker system prune -f
```

---

## 18. 日常运维

```bash
cd /opt/realman/app
docker compose ps
docker stats --no-stream
docker compose restart realman-iot
docker logs -f realman-iot --tail 200
grep "ERROR" /opt/realman/iot/logs/realman-iot.log | tail -20
```

**Loki LogQL 示例：**

```logql
{app="realman-iot-szy", level="ERROR"}
{app=~"realman-.*"} |= "traceId=abc123"
sum by (app) (rate({level="ERROR"}[1m]))
```

**crontab：**

```cron
0 3 * * * find /opt/realman/backup -name "*.sql.gz" -mtime +7 -delete
0 4 * * 0 docker system prune -f
```

---

## 19. 客户交付清单

### 19.1 环境信息


| 项目            | 记录  |
| ------------- | --- |
| 场景 A/B/C      |     |
| `<SERVER_IP>` |     |
| Git SHA       |     |
| 日期 / 执行人      |     |


### 19.2 访问地址


| 系统                     | URL                               |
| ---------------------- | --------------------------------- |
| 管理后台                   | `http://<SERVER_IP>/gln_admin/`   |
| System API             | `http://<SERVER_IP>/realman-boot` |
| IoT API                | `http://<SERVER_IP>/realman-iot`  |
| Nacos / Grafana / EMQX | 见 §8.9 表                          |
| MQTT Broker            | `<SERVER_IP>:1883`                |


### 19.3 保密附件

- SSH、数据库、Redis、Nacos、Grafana、EMQX、MinIO 密码
- `SIGNATURE_SECRET`

### 19.4 验收

- §15 全部通过
- 防火墙 §3.2
- Nacos 生产项（knife4j、lowCodeMode）
- 备份策略已说明

---

## 20. 附录

### 附录 A：首次部署勾检表（云服务器，可打印）

```
【阶段 0～1 准备】
[ ] §2 信息表（VPC、ECS 内网 IP、RDS 内网地址、公网 IP）
[ ] §2.3 交付包：4 份 SQL + 2 个 jar + Dockerfile + 前端（如有）
[ ] 场景 A/B 已确认；§6.2 Docker 已安装；§6.4 端口与目录权限 OK

【阶段 2 数据库】
[ ] RDS 三库已建；白名单已加 ECS 内网 IP
[ ] §4.3 ping 通过；§4.4 四份 SQL 已导入

【阶段 3 配置】
[ ] §7 目录完整；§8 redis/loki/grafana/nginx/.env 已写入
[ ] §9 compose 占位符已全部替换
[ ] nginx 含 resolver；location 为 /realman-boot（非 /realman-system）

【阶段 4 中间件】
[ ] Redis / Nacos / EMQX / MinIO / Loki / Grafana 均为 Up
[ ] curl Nacos :8848、Loki :3100/ready 通过

【阶段 5 控制台】
[ ] Nacos REALMAN_GROUP：realman-system.yaml + realman-iot.yaml
[ ] IoT yaml：discovery + darwin.enabled=false + mqtt.emqx.api-*
[ ] EMQX：Dashboard 改密；Built-in 用户 iot-platform
[ ] MinIO：iot-firmware、iot-slam 桶已建

【阶段 6～7 应用】
[ ] docker build 在 build/realman-system、build/realman-iot 目录
[ ] 日志 Started + Load config + IoT nacos register finished
[ ] EMQX HTTP Auth/ACL 已指向 realman-iot（§12.2）

【阶段 8 验收】
[ ] /realman-boot/actuator UP；/realman-iot/actuator UP
[ ] Nacos 服务列表见 realman-boot、realman-iot
[ ] §19 交付清单 + 密码附件
```

### 附录 B：端口速查


| 组件      | 容器名                   | 宿主机       | 对外      |
| ------- | --------------------- | --------- | ------- |
| Nginx   | realman-nginx         | 80        | 开放      |
| EMQX    | realman-emqx          | 1883/8083 | 设备网络    |
| System  | realman-system        | 8080      | 经 Nginx |
| IoT     | realman-iot           | 8085      | 经 Nginx |
| Redis   | realman-redis         | 6379      | 不开放     |
| Nacos   | realman-nacos         | 8848      | 经 Nginx |
| MySQL   | realman-mysql / 云 RDS | 3306      | 不开放/白名单 |
| Grafana | realman-grafana       | 3000      | 经 Nginx |


### 附录 C：生产风险（单机）


| 风险      | 说明            | 建议           |
| ------- | ------------- | ------------ |
| OTA 分片  | 可能用本地 `/tmp`  | 多实例前改 MinIO  |
| Session | 本地 Map        | 多机需 Redis 会话 |
| 磁盘      | Loki/MinIO 增长 | 监控 `/opt`    |
| RDS 连接数 | 扩容后倍增         | 升配或连接池       |


### 附录 D：扩容节点


| 条件            | 动作        |
| ------------- | --------- |
| 设备 > 1000     | EMQX 独立部署 |
| RDS CPU > 70% | 升配/读写分离   |
| 内存 > 80%      | 升配应用机     |
| 多节点 HA        | 集群化改造     |


---

### 附录 E：文档与代码一致性（维护用）


| 项                              | 当前约定                                             |
| ------------------------------ | ------------------------------------------------ |
| 前端 API 前缀                      | `/realman-boot`                                  |
| `application.yml` context-path | `/realman-boot`                                  |
| Nginx location                 | `/realman-boot` → `realman-system:8080`          |
| Nacos system DataId            | `realman-system.yaml`                            |
| Nacos iot DataId               | `realman-iot.yaml`                               |
| 打包命令                           | `mvn ... -Pprod`                                 |
| 初始化 SQL                        | `db/` + `realman-boot-iot/sql/iot_init.sql`      |
| IoT 环境变量                       | 无加密主密钥；连接鉴权靠 DB `device_secret` + EMQX HTTP Auth |


---

**文档维护：** compose 或配置变更时同步更新本手册 **v3.0**。