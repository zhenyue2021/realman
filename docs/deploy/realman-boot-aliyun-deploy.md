# realman-boot 阿里云部署手册（Docker Compose）

> 版本：v1.3 | 日期：2026-04-15 | 维护人：Lorete  
> 适用环境：阿里云 ECS 全新单机 Docker Compose 部署

本文档由 `docs/runbook/realman-boot-aliyun-runbook.md`（完整运行手册）与 `docs/runbook/aliyun-deployment-guide.md`（IoT/EMQX 补充说明）合并而成：以运行手册为骨架，并入设备加密环境变量、一机一密 EMQX HTTP 认证、IoT 库表与 MinIO 策略、生产风险等增量内容；冲突处以可执行步骤与仓库实际路径为准。

## 目录

1. [服务器规格建议](#1-服务器规格建议)
2. [整体架构](#2-整体架构)
3. [部署前 Checklist](#3-部署前-checklist)
4. [服务器初始化](#4-服务器初始化)
5. [目录结构与基础配置文件](#5-目录结构与基础配置文件)
6. [Docker Compose（完整生产版）](#6-docker-compose完整生产版)
7. [Dockerfile](#7-dockerfile)
8. [应用构建与镜像打包](#8-应用构建与镜像打包)
9. [部署步骤（按序执行）](#9-部署步骤按序执行)
10. [验证步骤](#10-验证步骤)
11. [回滚方案](#11-回滚方案)
12. [日常运维参考](#12-日常运维参考)
13. [生产环境风险与多实例注意事项](#13-生产环境风险与多实例注意事项)
14. [附录](#14-附录)

---

## 1. 服务器规格建议

### 资源评估


| 服务                            | 内存估算         | CPU         |
| ----------------------------- | ------------ | ----------- |
| MySQL 8.0（buffer-pool 512M）   | ~1.5 GB      | 1-2 core    |
| Redis 7.2                     | ~512 MB      | 0.5 core    |
| Nacos v2.3.2（JVM 512M）        | ~768 MB      | 0.5 core    |
| EMQX 5.8                      | ~512 MB      | 1 core      |
| MinIO                         | ~512 MB      | 0.5 core    |
| XXL-Job Admin                 | ~384 MB      | 0.5 core    |
| realman-system（Spring Boot 3） | ~1.5 GB      | 1-2 core    |
| realman-iot（Spring Boot 3）    | ~1.5 GB      | 1-2 core    |
| Zipkin                        | ~512 MB      | 0.5 core    |
| Loki                          | ~512 MB      | 0.5 core    |
| Grafana                       | ~512 MB      | 0.5 core    |
| OS + Docker Daemon 开销         | ~2 GB        | 1 core      |
| **合计**                        | **~11.2 GB** | **~9 core** |


### 推荐规格


| 场景         | ECS 规格                     | 系统盘            | 数据盘                | 公网带宽           |
| ---------- | -------------------------- | -------------- | ------------------ | -------------- |
| 开发/测试      | ecs.g7.xlarge（4C/16G）      | ESSD PL0 100GB | ESSD PL1 200GB     | 3Mbps          |
| **生产（推荐）** | **ecs.g7.2xlarge（8C/32G）** | ESSD PL0 100GB | **ESSD PL1 500GB** | 5Mbps 或 EIP 按量 |


> - 持久化数据统一写入 `/opt/realman`（系统盘；若后续挂载独立数据盘，将 `/opt/realman` 整体迁移或软链接到挂载点即可）
> - 设备规模超 1,000 台后，评估将 MySQL/EMQX 拆分到独立机器

### 安全组端口清单

> 引入 Nginx 后，运维控制台统一经 `:80` 访问，大幅收窄对外暴露端口。


| 端口          | 服务                   | 安全组规则                             |
| ----------- | -------------------- | --------------------------------- |
| 22          | SSH                  | **仅限运维 IP 白名单**                   |
| 80          | Nginx（统一入口）          | 公网开放（运维访问所有控制台）                   |
| 9999        | Spring Cloud Gateway | 公网开放（前端/API）                      |
| 1883        | EMQX MQTT TCP        | 公网开放（设备接入）                        |
| 8083        | EMQX MQTT WebSocket  | 公网开放（设备接入）                        |
| 8883        | EMQX MQTT TLS        | 公网开放（可选，需证书）                      |
| 3306        | MySQL                | **关闭**（SSH 隧道访问）                  |
| 6379        | Redis                | **关闭**（容器内访问）                     |
| 8848 / 9848 | Nacos                | **关闭**（经 Nginx /nacos 访问）         |
| 18083       | EMQX Dashboard       | **关闭**（经 Nginx /emqx 访问）          |
| 3000        | Grafana              | **关闭**（经 Nginx /grafana 访问）       |
| 9411        | Zipkin UI            | **关闭**（经 Nginx /zipkin 访问）        |
| 9001        | MinIO S3 API         | **关闭**（容器内访问）                     |
| 9090        | MinIO Console        | **关闭**（经 Nginx /minio/ 访问）        |
| 9080        | XXL-Job Admin        | **关闭**（经 Nginx /xxl-job-admin 访问） |
| 3100        | Loki                 | **关闭**（容器内访问）                     |


---

## 2. 整体架构

```
Internet
    │
    ├──────────────────────────────────────────────┐
    │ 设备接入                                      │ Web/API 访问
    ▼                                              ▼
[EMQX :1883/:8083]              [Spring Cloud Gateway :9999]
    │                                    │
    │ MQTT 消息                  ┌────────┴────────┐
    ▼                           ▼                 ▼
[realman-iot :8085]    [realman-system :8080]  [XXL-Job :9080]
    │                           │
    └──────────┬────────────────┘
               ▼
    ┌──────────────────────────────┐
    │         基础设施层            │
    │  MySQL:3306  Redis:6379      │
    │  Nacos:8848  MinIO:9001      │
    └──────────────────────────────┘
               │ 日志直推（loki4j）
               ▼
    ┌──────────────────────────────┐
    │        可观测性层             │
    │  Zipkin:9411  链路追踪        │
    │  Loki:3100    日志聚合        │
    │  Grafana:3000 可视化面板      │
    │    └── Datasource: Loki      │
    │    └── Datasource: Zipkin    │
    └──────────────────────────────┘
```

**日志链路说明**

```
应用（loki4j Appender）
    │  HTTP POST /loki/api/v1/push
    ▼
Loki（存储索引：app / host / level）
    │  标签过滤 + 全文检索
    ▼
Grafana（Explore 页面 / Dashboard）
    │  LogQL 关联 traceId
    ▼
Zipkin（链路详情）
```

---

## 3. 部署前 Checklist

### 3.1 代码与配置

- 所有代码已合并到 `master` 分支，CI 构建通过
- 生产 Nacos 配置已确认以下项：
  - `TRACE_SAMPLING=0.1`（生产采样率 10%）
  - `knife4j.production: true`（关闭 Swagger 公网访问）
  - `jeecg.firewall.lowCodeMode: prod`（关闭在线开发功能）
  - `server.error.include-stacktrace: NEVER`
  - `jeecg.signatureSecret` 已替换为生产专用密钥
- **IoT 设备通信**：宿主机 `/opt/realman/app/.env` 中已准备 `DEVICE_ENCRYPT_MASTER_KEY`（32 字节随机串，用于一机一密；丢失将导致已注册设备无法鉴权）；按需配置 `DEVICE_STREAM_SECRET`（流媒体相关）

### 3.2 数据库

- 本次迭代正向 DDL 脚本已准备
- 对应回滚 DDL 脚本已准备
- 已在测试环境执行验证通过
- **首次部署**：`jeecg-boot`、`nacos`、`xxl_job` 数据库初始化 SQL 已就绪
- **IoT 业务库**：已准备 `realman-boot-iot/sql/` 下初始化脚本（至少包含 `iot_init.sql`；若仓库另有 SLAM/OTA 等增量脚本，一并纳入发布包并在测试环境验证）
- **更新部署**：已对生产库执行备份（见 [9.2](#92-生产数据库备份更新部署必做首次跳过)）

### 3.3 中间件依赖确认

- MySQL 健康，三个库已创建
- Redis 健康，密码已配置并同步到 Nacos
- Nacos 健康，`REALMAN_GROUP` 下配置已录入
- EMQX 认证用户 `iot-platform` 已创建
- MinIO Bucket 已创建：`iot-firmware`、`iot-slam`
- Loki 健康（`/ready` 返回 200）
- Grafana 健康，Loki / Zipkin 数据源已自动注入

### 3.4 集群安全（对照 `docs/改造清单.md`）

- P0-1 Session 分布式：单机豁免，后续扩容前必须完成
- P0-2 OTA 分片本地存储（`/tmp/iot-ota-chunks`）：单机可运行，扩容前改用 MinIO
- P0-3 短信限流本地 Map（`DySmsLimit.java`）：评估是否已修复
- 确认无新增 `static Map` 本地缓存
- 确认 `@Cacheable` 指向 Redis

### 3.5 安全核查

- 代码中无硬编码密钥
- 日志不含密码、手机号等敏感字段
- 文件上传接口已配置 MIME 校验和大小限制
- Grafana 默认密码（admin/admin）首次登录后已修改

---

## 4. 服务器初始化

```bash
# ——— 4.1 系统更新 ———
sudo apt update && sudo apt upgrade -y   # Ubuntu 22.04

# ——— 4.2 安装 Docker ———
curl -fsSL https://get.docker.com | sh
sudo systemctl enable docker && sudo systemctl start docker
sudo usermod -aG docker $USER && newgrp docker
docker --version  # 验证输出 Docker version 29.4.1

# ——— 4.3 安装 Docker Compose v2 ———
sudo apt install docker-compose-plugin -y
docker compose version   # 验证输出 v2.x.x

# ——— 4.4 创建应用数据根目录（服务器无独立数据盘，直接使用系统盘 /opt）———
# 若后续挂载独立数据盘（假设 /dev/vdb），执行以下注释命令后将目录软链接到挂载点：
#   sudo mkfs.ext4 /dev/vdb
#   sudo mount /dev/vdb /mnt/data
#   echo "/dev/vdb  /mnt/data  ext4  defaults  0  2" | sudo tee -a /etc/fstab
#   sudo mv /opt/realman /mnt/data/realman && sudo ln -s /mnt/data/realman /opt/realman
sudo mkdir -p /opt/realman

# ——— 4.5 内核参数优化 ———
cat >> /etc/sysctl.conf << 'EOF'
vm.overcommit_memory=1
net.core.somaxconn=65535
net.ipv4.tcp_max_syn_backlog=65535
fs.file-max=1000000
EOF
sudo sysctl -p

# ——— 4.6 文件描述符限制（EMQX 需要）———
cat >> /etc/security/limits.conf << 'EOF'
* soft nofile 1000000
* hard nofile 1000000
EOF
```

---

## 5. 目录结构与基础配置文件

### 5.1 创建所有数据目录

```bash
mkdir -p /opt/realman/{mysql/{data,conf,logs},redis/{data,conf,logs},nacos/logs,emqx/{data,log},minio/data,xxljob/logs,system/{upload,logs},iot/logs,loki/{data,config},grafana/{data,provisioning/{datasources,dashboards}},backup,app/{sql/migrations,build/{realman-system/target,realman-iot/target},nginx}}
```

完整目录树：

```
/opt/realman/
├── app/
│   ├── docker-compose.yml
│   ├── .env
│   ├── sql/                        ← 初始化 / 迁移 SQL 脚本（本地上传）
│   │   ├── jeecg_boot_init.sql
│   │   ├── nacos_init.sql
│   │   ├── xxljob_init.sql
│   │   └── migrations/             ← 每次迭代的正向 + 回滚脚本
│   └── build/                      ← docker build 构建上下文（本地上传）
│       ├── realman-system/
│       │   ├── Dockerfile
│       │   └── target/
│       │       └── realman-system-start-*.jar
│       └── realman-iot/
│           ├── Dockerfile
│           └── target/
│               └── realman-boot-iot-start-*.jar
├── mysql/{data,conf,logs}
├── redis/{data,conf,logs}
├── nacos/logs
├── emqx/{data,log}
├── minio/data
├── xxljob/logs
├── system/{upload,logs}        ← realman-system 上传文件 / 日志
├── iot/logs                    ← realman-iot 日志
├── loki/{data,config}          ← Loki 存储 / 配置
├── grafana/{data,provisioning} ← Grafana 数据 / 自动配置
└── backup/                     ← 数据库定期备份
```

### 5.2 MySQL 配置

```bash
cat > /opt/realman/mysql/conf/my.cnf << 'EOF'
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
EOF
```

### 5.3 Redis 配置

```bash
cat > /opt/realman/redis/conf/redis.conf << 'EOF'
bind 0.0.0.0
port 6379
requirepass your_strong_redis_password
maxmemory 4gb
maxmemory-policy allkeys-lru
appendonly yes
appendfsync everysec
no-appendfsync-on-rewrite yes
save 900 1
save 300 10
loglevel notice
EOF
```

> ⚠️ `your_strong_redis_password` 需同步写入 Nacos 的 Redis 连接配置。

### 5.4 Loki 配置

```bash
cat > /opt/realman/loki/config/loki-config.yml << 'EOF'
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
  # 单流最大入库速率
  ingestion_rate_mb: 16
  ingestion_burst_size_mb: 32
  # 单次查询最大返回行数
  max_entries_limit_per_query: 10000
  # 日志保留 30 天
  retention_period: 720h

compactor:
  working_directory: /loki/data/compactor
  # Loki 3.x：开启 retention 时必须配置 delete_request_store（否则启动报错）
  delete_request_store: filesystem
  retention_enabled: true
  retention_delete_delay: 2h

ruler:
  alertmanager_url: http://localhost:9093
EOF
```

### 5.5 Grafana 数据源自动配置

```bash
# 数据源：Loki + Zipkin
cat > /opt/realman/grafana/provisioning/datasources/datasources.yml << 'EOF'
apiVersion: 1

datasources:
  # ——— Loki 日志 ———
  - name: Loki
    type: loki
    access: proxy
    url: http://realman-loki:3100
    isDefault: true
    jsonData:
      maxLines: 1000
      # 关联 Zipkin traceId（点击日志行可跳转链路详情）
      derivedFields:
        - datasourceUid: zipkin-ds
          matcherRegex: 'traceId=(\w+)'
          name: TraceID
          url: '$${__value.raw}'
    editable: true

  # ——— Zipkin 链路追踪 ———
  - name: Zipkin
    type: zipkin
    uid: zipkin-ds
    access: proxy
    url: http://realman-zipkin:9411
    editable: true
EOF
```

> Loki `derivedFields` 配置实现：在 Grafana Explore 页查看日志时，点击 `traceId` 值可直接跳转到 Zipkin 对应链路详情页。

### 5.6 Grafana Dashboard 目录配置

```bash
cat > /opt/realman/grafana/provisioning/dashboards/dashboards.yml << 'EOF'
apiVersion: 1

providers:
  - name: default
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
EOF

# 创建 Dashboard 存放目录（后续可导入 JSON 文件）
mkdir -p /opt/realman/grafana/data/dashboards
```

**子路径下改密码 / 保存数据源时提示 `origin not allowed`：** ① 反代须把**真实 Host** 传到 Grafana（`Host` 与 `X-Forwarded-Host` 均为浏览器里访问的 IP/域名），见本仓库 `nginx.conf` 的 `location /grafana/` 与 `location /grafana/api/live/`。② `GF_SERVER_ROOT_URL`（建议 `.../grafana/` 带尾斜杠）与 `GF_SERVER_DOMAIN` 与上述 Host 一致。③ `GF_SECURITY_CSRF_TRUSTED_ORIGINS` 为**纯主机名**、空格分隔，如 `10.10.17.237` 或 `grafana.corp.lan`（Grafana 11 在 CSRF 里用 Origin 的**主机名**与此逐项比较，**不要**写 `http://10.10.17.237` 这种带协议的 URL，否则永不对）。**若仍失败**：`docker compose up -d --force-recreate grafana`，再执行 `docker exec realman-grafana sh -c "env | grep -E ^GF_SERVER"` 确认变量已进容器。浏览器**不要混用**内网 IP 与主机名/localhost；若用非 80 端口，在 `root_url` 中写出该端口。

### 5.7 环境变量文件（.env）

```bash
cat > /opt/realman/app/.env << 'EOF'
# ——— 链路追踪采样率（生产建议 0.1） ———
TRACE_SAMPLING=0.1

# ——— Zipkin 上报地址（容器名在 realman-net 内可解析） ———
ZIPKIN_ENDPOINT=http://realman-zipkin:9411/api/v2/spans

# ——— Loki 推送地址（容器名在 realman-net 内可解析） ———
LOKI_URL=http://realman-loki:3100/loki/api/v1/push

# ——— Grafana 根 URL（与浏览器地址栏一致，避免 origin not allowed；与 compose 默认相同时可省略本项） ———
# 与浏览器 Host 一致；subpath 建议带尾斜杠
# GRAFANA_ROOT_URL=http://10.10.17.237/grafana/
# GRAFANA_DOMAIN=10.10.17.237
# 仅主机名，空格分隔；勿带 http://（Grafana 与 Origin 的主机名比较）
# GRAFANA_CSRF_TRUSTED_ORIGINS=10.10.17.237

# ——— Spring 激活 Profile ———
SPRING_PROFILES_ACTIVE=prod

# ——— IoT：设备通信加密（一机一密），须与 Nacos/业务约定一致 ———
# 示例：openssl rand -base64 32
DEVICE_ENCRYPT_MASTER_KEY=your-32-bytes-secure-random-string 
EOF
```

> `env` IoT：设备通信加密 DEVICE_ENCRYPT_MASTER_KEY：按需配置

---

## 6. Docker Compose（完整生产版）

将以下内容保存为 `/opt/realman/app/docker-compose.yml`：

```yaml
services:

  # ============================================================
  # 反向代理 — Nginx（统一入口，端口 80）
  # 各服务访问地址：
  #   GLN Teleop: http://<host>/gln_teleop/
  #   Nacos:      http://<host>/nacos
  #   XXL-Job:    http://<host>/xxl-job-admin
  #   Grafana:    http://<host>/grafana
  #   Zipkin:     http://<host>/zipkin
  #   EMQX Dashboard: http://<host>/emqx
  #   MinIO Console:  http://<host>/minio/
  #   device whoami: http://<host>/api/v1/device/whoami（反代宿主机 9091，见 nginx + extra_hosts）
  # ============================================================
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
    # 供 nginx 访问「宿主机」上的服务（如 device whoami :9091）；127.0.0.1 在容器内指向容器自身
    extra_hosts:
      - "host.docker.internal:host-gateway"
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — MySQL 8.0
  # ============================================================
  mysql:
    image: mysql:8.0
    container_name: realman-mysql
    restart: always
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root@123456
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
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot@123456"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — Redis 7.2
  # ============================================================
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
      test: ["CMD", "redis-cli", "-a", "your_strong_redis_password", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — Nacos v2.3.2（standalone，持久化到 MySQL）
  # ============================================================
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
      MYSQL_SERVICE_PASSWORD: root@123456
      JVM_XMS: 256m
      JVM_XMX: 512m
    ports:
      - "8848:8848"
      - "9848:9848"
    volumes:
      - /opt/realman/nacos/logs:/home/nacos/logs
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — EMQX 5.8（MQTT Broker）
  # 首次启动后在 Dashboard(18083) 创建认证用户:
  #   用户名: iot-platform  密码: realman123
  # ============================================================
  emqx:
    image: emqx:5.8
    container_name: realman-emqx
    restart: always
    environment:
      TZ: Asia/Shanghai
      EMQX_NODE__NAME: emqx@realman-emqx
    ports:
      - "1883:1883"     # MQTT TCP（设备接入，安全组开放）
      - "8083:8083"     # MQTT WebSocket（设备接入，安全组开放）
      - "8883:8883"     # MQTT TLS（可选，安全组开放）
      # 18083 Dashboard 经 Nginx /emqx 代理，无需对外暴露
    volumes:
      - /opt/realman/emqx/data:/opt/emqx/data
      - /opt/realman/emqx/log:/opt/emqx/log
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — MinIO
  # S3 API: 9001   Console: http://<host>:9090
  # 首次启动后创建 Bucket: iot-firmware、iot-slam
  # ============================================================
  minio:
    image: minio/minio:RELEASE.2024-01-16T16-07-38Z
    container_name: realman-minio
    restart: always
    environment:
      TZ: Asia/Shanghai
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: realman123
      MINIO_BROWSER_REDIRECT_URL: http://10.10.17.237/minio/
    ports:
      - "9001:9000"     # S3 API（应用内网访问）
      # Console 经 Nginx /minio/ 代理，无需对外暴露
    volumes:
      - /opt/realman/minio/data:/data
    command: server /data --console-address ":9001"
    networks:
      - realman-net

  # ============================================================
  # 基础设施 — XXL-Job Admin 2.4.0
  # 控制台: http://<host>:9080/xxl-job-admin  账号: admin/123456
  # ============================================================
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
        --spring.datasource.password=root@123456
        --xxl.job.accessToken=
    ports:
      - "9080:8080"
    volumes:
      - /opt/realman/xxljob/logs:/data/applogs
    networks:
      - realman-net

  # ============================================================
  # 可观测性 — Zipkin（链路追踪，内存存储，重启丢失）
  # 经 Nginx 访问：http://<host>/zipkin
  # ============================================================
  zipkin:
    image: openzipkin/zipkin:3
    container_name: realman-zipkin
    restart: always
    environment:
      TZ: Asia/Shanghai
      ZIPKIN_UI_BASEPATH: /zipkin
    networks:
      - realman-net

  # ============================================================
  # 可观测性 — Loki（日志聚合，文件系统存储，保留 30 天）
  # 应用通过 loki4j Appender 直推，不对外暴露端口
  # ============================================================
  loki:
    image: grafana/loki:3.0.0
    container_name: realman-loki
    restart: always
    ports:
      - "3100:3100"    # 内网可访问，安全组不对公网开放
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

  # ============================================================
  # 可观测性 — Grafana（日志 + 链路可视化）
  # 经 Nginx 访问：http://<host>/grafana  账号: admin / admin（立即修改）
  # Datasource 已通过 provisioning 自动注入: Loki + Zipkin
  # ============================================================
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
      GF_SECURITY_ADMIN_PASSWORD: admin   # 首次登录后立即在 UI 修改
      # 必须与「浏览器地址栏里访问 Grafana 的完整根 URL」一致；官方 subpath 常见写法带尾部斜杠
      # 内网固定用 10.10.17.237 时可不填 .env；与默认值不一致时设 GRAFANA_ROOT_URL / GRAFANA_DOMAIN / GRAFANA_CSRF_TRUSTED_ORIGINS
      GF_SERVER_ROOT_URL: ${GRAFANA_ROOT_URL:-http://10.10.17.237/grafana/}
      GF_SERVER_DOMAIN: ${GRAFANA_DOMAIN:-10.10.17.237}
      GF_SERVER_SERVE_FROM_SUB_PATH: "true"
      # CSRF：csrf_trusted_origins 在 Grafana 内与 Origin 的「主机名」比较，勿写含 http:// 的整段 URL
      GF_SECURITY_CSRF_TRUSTED_ORIGINS: ${GRAFANA_CSRF_TRUSTED_ORIGINS:-10.10.17.237}
      GF_SECURITY_CSRF_ADDITIONAL_HEADERS: "X-Forwarded-Host X-Original-Host"
      GF_LOG_LEVEL: warn
      GF_AUTH_ANONYMOUS_ENABLED: "false"
    volumes:
      - /opt/realman/grafana/data:/var/lib/grafana
      - /opt/realman/grafana/provisioning:/etc/grafana/provisioning
      - /opt/realman/grafana/data/dashboards:/var/lib/grafana/dashboards
    networks:
      - realman-net

  # ============================================================
  # 应用服务 — realman-system（系统管理：用户/权限/字典/监控）
  # 端口 8080，context-path: /realman-boot
  # ============================================================
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

  # ============================================================
  # 应用服务 — realman-iot（设备/MQTT/SLAM/OTA/WebRTC）
  # 端口 8085，context-path: /realman-iot
  # ============================================================
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

**gln_teleop / gln_admin 访问 404：** 在 `nginx` 服务中已挂载 `frontend/gln_teleop`、`frontend/gln_admin` 到容器内 `.../gln_teleop`、`.../gln_admin` 后，执行 `docker compose up -d nginx`；`docker exec realman-nginx ls /usr/share/nginx/html/gln_teleop` 与 `.../gln_admin` 下应能分别看到 `index.html`。构建时各项目 `base` 须为 `/gln_teleop/` 与 `/gln_admin/`，与 Nginx 子路径一致。

---

## 7. Dockerfile

### 7.1 realman-system

路径：`realman-module-system/realman-system-start/Dockerfile`

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

# MaxRAMPercentage=70：最多使用容器 memory limit 的 70%，配合 docker stats 监控
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

### 7.2 realman-iot

路径：`realman-boot-iot/realman-boot-iot-start/Dockerfile`

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

## 8. 应用构建与镜像打包

> 代码库位于内网，无法在服务器上直接拉取。流程为：**本地打包 → 上传 jar + Dockerfile → 服务器构建镜像**。

### 8.1 本地打包（开发机执行）

```bash
# 记录版本号（用于镜像 tag，方便回滚）
GIT_SHA=$(git rev-parse --short HEAD)
echo "本次发布版本：${GIT_SHA}"

# 构建 realman-system
mvn clean package \
  -pl realman-module-system/realman-system-start \
  -am -DskipTests -Pprod

# 构建 realman-iot
mvn clean package \
  -pl realman-boot-iot/realman-boot-iot-start \
  -am -DskipTests -Pprod
```

### 8.2 上传到服务器（开发机执行）

```bash
SERVER="root@<服务器公网IP>"

# ——— realman-system ———
scp realman-module-system/realman-system-start/Dockerfile \
    ${SERVER}:/opt/realman/app/build/realman-system/

scp realman-module-system/realman-system-start/target/realman-system-start-*.jar \
    ${SERVER}:/opt/realman/app/build/realman-system/target/

# ——— realman-iot ———
scp realman-boot-iot/realman-boot-iot-start/Dockerfile \
    ${SERVER}:/opt/realman/app/build/realman-iot/

scp realman-boot-iot/realman-boot-iot-start/target/realman-boot-iot-start-*.jar \
    ${SERVER}:/opt/realman/app/build/realman-iot/target/

echo "上传完成"
```

> 首次部署还需上传 SQL 脚本：
>
> ```bash
> scp sql/jeecg_boot_init.sql sql/nacos_init.sql sql/xxljob_init.sql \
>     ${SERVER}:/opt/realman/app/sql/
> # IoT 初始化脚本
> scp realman-boot-iot/sql/iot_init.sql \
>     ${SERVER}:/opt/realman/app/sql/
> ```

### 8.3 服务器构建镜像（服务器执行）

```bash
cd /opt/realman/app

# 构建（GIT_SHA 需与开发机一致，手动填入或通过环境变量传入）
GIT_SHA="<填入开发机输出的 SHA>"

docker build \
  -t realman/realman-system:${GIT_SHA} \
  -t realman/realman-system:latest \
  build/realman-system/

docker build \
  -t realman/realman-iot:${GIT_SHA} \
  -t realman/realman-iot:latest \
  build/realman-iot/

echo "镜像构建完成，版本：${GIT_SHA}"
```

---

## 9. 部署步骤（按序执行）

### 9.1 准备工作（首次）

```bash
# ——— 开发机执行：上传运行配置 ———
SERVER="root@<服务器公网IP>"

scp docker-compose.yml         ${SERVER}:/opt/realman/app/
scp .env                       ${SERVER}:/opt/realman/app/
# 配置文件（my.cnf / redis.conf / loki-config.yml / datasources.yml 等）
# 按第 5 节在服务器上直接创建，或本地写好后 scp 上传

# ——— 服务器执行 ———
ssh ${SERVER}
cd /opt/realman/app
```

### 9.2 生产数据库备份（更新部署必做，首次跳过）

```bash
BACKUP_FILE="/opt/realman/backup/jeecg-boot_$(date +%Y%m%d_%H%M%S).sql.gz"
docker exec realman-mysql mysqldump \
  -uroot -p'root@123456' \
  --single-transaction --quick \
  'jeecg-boot' | gzip > ${BACKUP_FILE}
echo "备份完成：${BACKUP_FILE}（$(du -sh ${BACKUP_FILE} | cut -f1)）"
```

### 9.3 启动基础数据层

```bash
cd /opt/realman/app

docker compose up -d mysql redis
echo "等待 MySQL / Redis 就绪..."

# 等待 MySQL healthcheck 通过（最多 60 秒）
until docker inspect realman-mysql --format='{{.State.Health.Status}}' | grep -q healthy; do
  sleep 5; echo "  等待 MySQL..."
done
echo "MySQL 已就绪"
```

### 9.4 数据库初始化（首次部署）

```bash
# 创建业务数据库（注意 jeecg-boot 含连字符，需反引号）
docker exec -i realman-mysql mysql -uroot -p'root@123456' << 'SQL'
CREATE DATABASE IF NOT EXISTS `realman-boot`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nacos
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS xxl_job
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

# 导入初始化 SQL（路径以发布包为准，以下为常见命名示例）
docker exec -i realman-mysql mysql -uroot -p'root@123456' 'realman-boot' < ./sql/realman_boot_init.sql
docker exec -i realman-mysql mysql -uroot -p'root@123456' nacos         < ./sql/nacos_init.sql
docker exec -i realman-mysql mysql -uroot -p'root@123456' xxl_job       < ./sql/tables_xxl_job.sql
docker exec -i realman-mysql mysql -uroot -p'root@123456' 'realman-boot' < ./sql/iot_init.sql

echo "数据库初始化完成"
```

> 若服务器上仅保留扁平化目录 `app/sql/`，请将 `iot_init.sql` 一并拷贝并改写上行 IoT 导入命令的重定向路径（例如 `< ./sql/iot_init.sql`）。

### 9.5 执行本次迭代 DDL（更新部署）

```bash
docker exec -i realman-mysql mysql -uroot -p'root@123456' 'jeecg-boot' \
  < ./sql/migrations/v$(cat VERSION)_migrate.sql
echo "DDL 迁移完成"
```

### 9.6 启动注册中心与中间件

```bash
# Nacos（依赖 MySQL）
docker compose up -d nacos
sleep 20

# 其余中间件全部启动
docker compose up -d emqx minio xxl-job-admin
sleep 15

docker compose ps   # 确认状态
```

### 9.7 启动可观测性服务

```bash
# Zipkin 和 Loki 先于应用启动，确保日志不丢失
docker compose up -d zipkin loki

# 等待 Loki ready
until curl -sf http://localhost:3100/ready; do
  sleep 5; echo "  等待 Loki..."
done
echo "Loki 已就绪"

# 启动 Grafana（依赖 Loki healthy）
docker compose up -d grafana
sleep 10
echo "Grafana 已启动：http://localhost:3000"
```

### 9.8 EMQX 初始化（首次部署）

访问 `http://<服务器IP>:18083`（admin / public）：

1. **立即修改 Dashboard 默认密码**
2. `Access Control → Authentication → Built-in Database → 添加用户`
  - 用户名：`iot-platform`，密码：`realman123`

#### 9.8.1 一机一密：EMQX HTTP 认证与 ACL（与 realman-iot 联调时配置）

双臂/具身设备若采用 **Per-Device Auth（一机一密）**，需在 EMQX Dashboard 中配置 HTTP 回调（与内置用户并存或按环境切换，以实际架构为准）。

**Authentication（AuthN）**：`Management → AuthN`，添加 **HTTP Server**：

- **URL**：`http://realman-iot:8085/realman-iot/internal/mqtt/auth`
- **Body**：

```json
{
  "clientid": "${clientid}",
  "username": "${username}",
  "password": "${password}",
  "peerhost": "${peerhost}"
}
```

**Authorization（AuthZ / ACL）**：`Management → AuthZ`，添加 **HTTP Server**：

- **URL**：`http://realman-iot:8085/realman-iot/internal/mqtt/acl`
- **Body**：

```json
{
  "clientid": "${clientid}",
  "username": "${username}",
  "topic": "${topic}",
  "action": "${action}"
}
```

> 容器网络内主机名 `realman-iot` 须与 `docker-compose.yml` 中 `realman-iot` 服务名一致；若修改服务名，请同步改 URL。

### 9.9 MinIO 初始化（首次部署）

```bash
# 新版 minio/mc 镜像入口为 `mc`，需显式指定 shell 才能用 sh -c
docker run --rm --network realman-net --entrypoint /bin/sh minio/mc:latest -c "
  mc alias set local http://realman-minio:9000 minioadmin realman123 &&
  mc mb --ignore-existing local/iot-firmware &&
  mc mb --ignore-existing local/iot-slam &&
  mc ls local
"
```

**可选（简化机器人端固件下载）**：在 MinIO Console（`:9090`）将 Bucket `iot-firmware` 配置为 **Public Read**，或按需配置匿名策略；若走签名 URL 则不必公开读。

### 9.10 录入 Nacos 生产配置

登录 `http://<服务器IP>:8848/nacos`（nacos / nacos）

命名空间：`public`，Group：`REALMAN_GROUP`

**DataId: `realman-boot.yaml`**（System 服务）

```yaml
spring:
  datasource:
    dynamic:
      datasource:
        master:
          url: jdbc:mysql://realman-mysql:3306/jeecg-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
          username: root
          password: root@123456
  data:
    redis:
      host: realman-redis
      port: 6379
      password: your_strong_redis_password
      database: 0

jeecg:
  uploadType: local
  path:
    upload: /opt/upFiles
    webapp: /opt/webapp
  signatureSecret: 替换为生产专用密钥（勿用默认值）
  firewall:
    lowCodeMode: prod
    dataSourceSafe: true
  redisson:
    address: realman-redis:6379
    password: your_strong_redis_password
    type: STANDALONE
    enabled: true
  xxljob:
    enabled: true
    adminAddresses: http://realman-xxljob:8080/xxl-job-admin
    appname: realman-boot

knife4j:
  production: true   # 关闭 Swagger 公网访问

server:
  error:
    include-stacktrace: NEVER
    include-exception: false
```

**DataId: `realman-iot.yaml`**（IoT 服务）

```yaml
spring:
  datasource:
    url: jdbc:mysql://realman-mysql:3306/jeecg-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: root@123456
  data:
    redis:
      host: realman-redis
      port: 6379
      password: your_strong_redis_password
      database: 0

mqtt:
  enabled: true
  broker:
    url: tcp://realman-emqx:1883
    username: iot-platform
    password: realman123
    client-id: iot-platform-server
    qos: 1
    keep-alive: 60

minio:
  endpoint: http://realman-minio:9000
  access-key: minioadmin
  secret-key: realman123
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
  signaling:
    server:
      auto-push:
        enabled: true   # 生产开启，启动时自动推送信令密钥
```

### 9.11 启动应用服务

> 镜像已在第 8.3 节本地构建完成（`realman/realman-system:latest`、`realman/realman-iot:latest`），无需从远程拉取。

```bash
cd /opt/realman/app

docker compose up -d realman-system
echo "等待 System 启动..."
sleep 30

docker compose up -d realman-iot
echo "等待 IoT 启动..."
sleep 30
```

### 9.12 确认启动日志

```bash
# 确认无 ERROR 级别启动异常
docker logs realman-system --tail 100 | grep -E "ERROR|Started|Exception"
docker logs realman-iot    --tail 100 | grep -E "ERROR|Started|Exception"

# 预期：
# Started RealmanSystemApplication in X.XXX seconds
# Started RealmanIotApplication in X.XXX seconds
```

---

## 10. 验证步骤

### 10.1 中间件健康确认

```bash
# MySQL
docker exec realman-mysql mysqladmin -uroot -p'root@123456' ping
# 预期：mysqld is alive

# Redis
docker exec realman-redis redis-cli -a your_strong_redis_password ping
# 预期：PONG

# Nacos
curl -sf http://localhost:8848/nacos/v1/console/health/liveness && echo " Nacos OK"

# EMQX
curl -s http://localhost:18083/api/v5/nodes -u admin:realman@123 \
  | python3 -m json.tool | grep '"status"'
# 预期："status": "running"

# MinIO
curl -sf http://localhost:9001/minio/health/live && echo " MinIO OK"

# Loki
curl -sf http://localhost:3100/ready && echo " Loki OK"
# 预期：ready

# Zipkin
curl -sf http://localhost:9411/health && echo " Zipkin OK"

# Grafana
curl -sf http://localhost:3000/api/health | python3 -m json.tool
# 预期：{"database":"ok","version":"11.0.0"}
```

### 10.2 应用健康检查

```bash
curl -s http://localhost:8080/realman-boot/actuator/health \
  | python3 -m json.tool | grep '"status"'
# 预期："status": "UP"

curl -s http://localhost:8085/realman-iot/actuator/health \
  | python3 -m json.tool | grep '"status"'
# 预期："status": "UP"
```

### 10.3 关键功能冒烟测试

```bash
# ——— 登录接口 ———
TOKEN=$(curl -s -X POST http://localhost:8080/realman-boot/sys/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","captcha":"","checkKey":""}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['token'])")
echo "Token 获取成功：${TOKEN:0:30}..."

# ——— MQTT 设备接入测试 ———
mosquitto_pub -h localhost -p 1883 \
  -u iot-platform -P realman123 \
  -t "device/test/heartbeat" \
  -m '{"deviceId":"test-001","status":"online"}' -d
# 预期：Connection Accepted / Published

# ——— MinIO Bucket 确认 ———
docker run --rm --network realman-net --entrypoint /bin/sh minio/mc:latest \
  -c "mc alias set local http://realman-minio:9000 minioadmin realman123 && mc ls local"
# 预期：iot-firmware  iot-slam
```

### 10.4 Loki 日志接收验证

```bash
# 查询最近 5 分钟内 realman-iot 的日志
curl -G "http://localhost:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={app="realman-iot-szy"}' \
  --data-urlencode "start=$(date -d '5 minutes ago' +%s)000000000" \
  --data-urlencode "end=$(date +%s)000000000" \
  --data-urlencode 'limit=10' \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d['data']['result']
print(f'流数量: {len(results)}')
if results:
    print('最新日志:')
    for v in results[0]['values'][-3:]:
        print(' ', v[1][:120])
"
# 预期：流数量 > 0，可看到实际日志内容
```

### 10.5 Grafana 数据源验证

1. 浏览器访问 `http://<服务器IP>:3000`，用 admin 账号登录（**登录后立即修改密码**）
2. 进入 `Connections → Data Sources`，确认：
  - **Loki** 数据源状态为绿色 `Data source connected and labels found`
  - **Zipkin** 数据源状态为绿色
3. 进入 `Explore`，选择 Loki 数据源，输入 LogQL：
  ```logql
   {app="realman-iot-szy"} |= "Started"
  ```
   确认能查到应用启动日志。
4. 选择一条含 `traceId=` 的日志行，点击 `traceId` 超链接，确认跳转到 Zipkin 对应链路详情。

### 10.6 Zipkin 链路追踪验证

访问 `http://<服务器IP>:9411`

- serviceName 选 `realman-boot` 或 `realman-iot-szy`
- 点击「Find Traces」，确认能查到刚才登录接口的链路
- 检查 span 节点完整，耗时分布合理

---

## 11. 回滚方案

### 11.1 应用服务回滚

```bash
# 部署前应记录的版本（写入发布记录）
ROLLBACK_SHA="abc1234"   # 上次发布的 git short SHA

# 停止当前版本
docker compose stop realman-iot realman-system

# 确认目标版本镜像已在本地（每次发布后 tag 保留在服务器上）
docker images | grep -E "realman-system|realman-iot"

# 直接用旧 tag 启动（镜像已在本地，无需拉取）
docker run -d --name realman-system-rb --network realman-net \
  --env-file /opt/realman/app/.env -e TZ=Asia/Shanghai \
  -p 8080:8080 \
  -v /opt/realman/system/upload:/opt/upFiles \
  -v /opt/realman/system/logs:/app/logs \
  realman/realman-system:${ROLLBACK_SHA}

docker run -d --name realman-iot-rb --network realman-net \
  --env-file /opt/realman/app/.env -e TZ=Asia/Shanghai \
  -p 8085:8085 \
  -v /opt/realman/iot/logs:/app/logs \
  realman/realman-iot:${ROLLBACK_SHA}

docker logs -f realman-system-rb --tail 50
```

### 11.2 数据库回滚

```bash
# ——— 方式一：执行回滚 DDL（推荐，每次迭代必须同时准备正向 + 回滚脚本）———
docker exec -i realman-mysql mysql -uroot -p'root@123456' 'jeecg-boot' \
  < ./sql/migrations/v$(cat VERSION)_rollback.sql

# ——— 方式二：恢复备份（会丢失备份后写入的数据，需提前评估）———
BACKUP_FILE="/opt/realman/backup/jeecg-boot_20260415_120000.sql.gz"
docker exec realman-mysql mysql -uroot -p'root@123456' -e \
  "DROP DATABASE \`jeecg-boot\`; CREATE DATABASE \`jeecg-boot\` CHARACTER SET utf8mb4;"
gunzip -c ${BACKUP_FILE} | \
  docker exec -i realman-mysql mysql -uroot -p'root@123456' 'jeecg-boot'
echo "数据库已恢复到备份时间点"
```

### 11.3 Nacos 配置回退

1. Nacos Console → 配置管理 → 对应 DataId → 历史版本 → 选择上一版本 → 回滚
2. 重启应用生效：

```bash
docker compose restart realman-system realman-iot
```

---

## 12. 日常运维参考

### 12.1 查看服务状态与资源消耗

```bash
docker compose ps
docker stats --no-stream --format \
  "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
```

### 12.2 查看应用日志

```bash
# 实时追踪
docker logs -f realman-iot --tail 200

# 持久化日志文件（JSON 格式，包含 traceId）
tail -f /opt/realman/iot/logs/realman-iot.log

# 快速过滤 ERROR
grep "ERROR" /opt/realman/iot/logs/realman-iot.log | tail -20
```

### 12.3 Loki 常用查询（在 Grafana Explore 中使用）

```logql
# 查看 IoT 服务所有 ERROR 日志
{app="realman-iot-szy", level="ERROR"}

# 按 traceId 追踪一次请求的完整日志
{app=~"realman-.*"} |= "traceId=abc123def456"

# 最近 1 小时 System 服务的 WARN 以上日志
{app="realman-boot", level=~"WARN|ERROR"} | line_format "{{.line}}"

# 统计每分钟 ERROR 数量（用于 Alert 规则）
sum by (app) (rate({level="ERROR"}[1m]))
```

### 12.4 重启单个服务

```bash
docker compose restart realman-iot
# 或重新拉取镜像并重建（发布新版本）
docker compose pull realman-iot && docker compose up -d --no-deps realman-iot
```

### 12.5 磁盘空间管理

```bash
df -h /opt
du -sh /opt/realman/*

# 清理悬空 Docker 镜像（不影响运行中容器）
docker system prune -f

# Loki 数据占用（正常情况下按 30 天自动 compaction）
du -sh /opt/realman/loki/data/
```

### 12.6 定时备份配置（crontab）

```bash
crontab -e
```

```cron
# 每天 02:00 备份业务数据库
0 2 * * * docker exec realman-mysql mysqldump -uroot -p'root@123456' --single-transaction 'jeecg-boot' | gzip > /opt/realman/backup/jeecg-boot_$(date +\%Y\%m\%d).sql.gz

# 每天 03:00 清理 7 天前的备份文件
0 3 * * * find /opt/realman/backup -name "*.sql.gz" -mtime +7 -delete

# 每周日 04:00 清理 Docker 悬空资源
0 4 * * 0 docker system prune -f
```

---

## 13. 生产环境风险与多实例注意事项

以下与 `docs/改造清单.md`、集群化设计文档及 [第 12 节](#12-日常运维参考) 对照阅读；单机 Compose 可运行，多实例前须逐项消除单点假设。


| 风险点                     | 说明                                  | 建议动作                                                 |
| ----------------------- | ----------------------------------- | ---------------------------------------------------- |
| **OTA 固件分片**            | 默认可能使用本地路径（如 `/tmp/iot-ota-chunks`） | 扩容多实例前改为共享存储或 MinIO 等统一对象存储                          |
| **SLAM 指令状态**           | 内存级等待 ACK                           | 服务重启可能导致 PENDING 丢失；可配合 XXL-Job 补偿或业务幂等设计            |
| **WebSocket / Session** | 本地 Session Map                      | 多机需 Redis Pub/Sub、统一会话或网关粘性会话等方案                     |
| **磁盘空间**                | 日志、SLAM 地图、备份                       | 监控 `/opt`，定期清理 `backup/` 中过期文件；Loki 按保留策略 Compaction |


---

## 14. 附录

### 附录 A：首次部署完整检查单

```
基础环境
[ ] /opt/realman 目录已创建（若挂载独立数据盘，确认已软链接到挂载点）
[ ] Docker + Docker Compose v2 已安装
[ ] 内核参数已优化（sysctl）
[ ] 所有目录已创建（步骤 5.1）

配置文件
[ ] MySQL my.cnf 已写入
[ ] Redis redis.conf 已写入（requirepass 已设置）
[ ] Loki loki-config.yml 已写入
[ ] Grafana provisioning/datasources.yml 已写入
[ ] .env 文件已创建（LOKI_URL / ZIPKIN_ENDPOINT / DEVICE_ENCRYPT_MASTER_KEY 已填写）

中间件
[ ] MySQL / Redis 已 healthy
[ ] 三个数据库已创建并导入初始 SQL（含 IoT：`realman-boot-iot/sql/iot_init.sql`）
[ ] Nacos 可访问，生产配置已录入（两个 DataId）
[ ] EMQX 认证用户 iot-platform 已添加，默认密码已修改；若启用一机一密，HTTP Auth/ACL 已配置（见 9.8.1）
[ ] MinIO Bucket iot-firmware / iot-slam 已创建

可观测性
[ ] Loki /ready 返回 200
[ ] Grafana 可登录，Loki 数据源状态绿色
[ ] Grafana Zipkin 数据源状态绿色
[ ] Grafana 默认密码已修改

应用
[ ] realman-system actuator/health 返回 UP
[ ] realman-iot actuator/health 返回 UP
[ ] 登录接口冒烟测试通过
[ ] MQTT 设备接入测试通过
[ ] Loki 可查到应用启动日志
[ ] Grafana traceId 跳转 Zipkin 验证通过

运维
[ ] 定时备份 crontab 已配置
[ ] 安全组端口已按清单收紧
```

### 附录 B：端口速查表


| 容器名             | 宿主机端口               | 访问地址 / 说明                        |
| --------------- | ------------------- | -------------------------------- |
| realman-mysql   | 3306                | 内网访问                             |
| realman-redis   | 6379                | 内网访问                             |
| realman-nacos   | 8848 / 9848         | `http://<IP>:8848/nacos`         |
| realman-emqx    | 1883 / 8083 / 18083 | Dashboard: `http://<IP>:18083`   |
| realman-minio   | 9001 / 9090         | Console: `http://<IP>:9090`      |
| realman-xxljob  | 9080                | `http://<IP>:9080/xxl-job-admin` |
| realman-zipkin  | 9411                | `http://<IP>:9411`               |
| realman-loki    | 3100                | 内网（容器间），不对外暴露                    |
| realman-grafana | 3000                | `http://<IP>:3000`               |
| realman-system  | 8080                | `http://<IP>:8080/realman-boot`  |
| realman-iot     | 8085                | `http://<IP>:8085/realman-iot`   |


### 附录 C：后续扩容评估节点


| 触发条件                    | 建议动作                        |
| ----------------------- | --------------------------- |
| 在线设备 > 1,000 台          | EMQX 独立部署，应用机与基础设施机拆分       |
| MySQL CPU > 70% 持续 5min | 升级规格或开读写分离                  |
| 整机内存 > 80%              | 升级到 ecs.g7.4xlarge（16C/64G） |
| Loki 数据目录 > 100GB       | 评估迁移到阿里云 OSS 作为 Loki 存储后端   |
| 需要多节点高可用                | 完成 `docs/改造清单.md` 全部集群化改造   |


