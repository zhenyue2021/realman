# RocketMQ 使用说明

- **适用场景**：达尔文数采平台对接（`darwin/` 集成包）
- **日期**：2026-04-27

**版本约定**（单一来源：父工程 `pom.xml`）：Java 侧 **`rocketmq-spring-boot-starter`** 使用属性 `rocketmq-spring.version`（当前 **2.3.1**）；独立部署 Broker / NameServer 时 Docker 镜像使用 **`apache/rocketmq:${rocketmq-broker-docker.version}`**（属性 `rocketmq-broker-docker.version`，当前 **5.3.2**）。NameServer与 Broker **必须**同一镜像 tag，且与达尔文侧 Broker  major 对齐（均为 5.x）。

---

## 一、核心概念速览

```
Producer（生产者）→ Broker（消息存储）→ Consumer（消费者）
                        ↑
                   NameServer（地址注册中心，相当于 DNS）
```

| 概念 | 说明 |
|---|---|
| **Topic** | 消息分类，类似"频道"。本项目用 `DARWIN_DEVICE_STATUS`、`DARWIN_WORKORDER_IN` 等 |
| **Tag** | Topic 下的二级过滤标签，本项目用 `ONLINE/OFFLINE/CREATE` 等 |
| **ConsumerGroup** | 同组内多实例**负载均衡**消费（一条消息只给一个实例）；不同组则**广播**（每组都收到） |

---

## 二、发送消息

本项目统一用 `RocketMQTemplate`，已封装在各 Producer 类里，不需要直接操作。

**格式规范：`Topic:Tag`**

```java
String destination = "DARWIN_DEVICE_STATUS:ONLINE";
rocketMQTemplate.send(destination,
    MessageBuilder.withPayload("JSON字符串").build());
```

**项目真实代码参考** — `DarwinDeviceStatusProducer.java`：

```java
String destination = DarwinTopicConstant.DEVICE_STATUS + ":" + tag;
rocketMQTemplate.send(destination,
        MessageBuilder.withPayload(objectMapper.writeValueAsString(dto)).build());
```

**三种发送方式对比**：

| 方式 | 方法 | 适用场景 |
|---|---|---|
| 同步 | `send()` | 需要确认发送结果（本项目使用） |
| 异步 | `asyncSend()` | 高吞吐，不阻塞主线程 |
| 单向 | `sendOneWay()` | 日志类，允许丢失 |

---

## 三、消费消息

实现 `RocketMQListener<String>` 接口，加 `@RocketMQMessageListener` 注解，Spring 自动注册消费者。

**项目真实代码参考** — `DarwinWorkOrderConsumer.java`：

```java
@Component
@RocketMQMessageListener(
    topic = "DARWIN_WORKORDER_IN",
    consumerGroup = "REALMAN_IOT_CONSUMER_GROUP",
    selectorExpression = "CREATE"   // 只消费 tag=CREATE 的消息；* 表示全部
)
public class DarwinWorkOrderConsumer implements RocketMQListener<String> {

    @Override
    public void onMessage(String message) {
        // message 就是发送时 payload 的字符串内容
        // 正常处理完：直接 return（自动 ACK）
        // 需要重试：抛 RuntimeException（RocketMQ 重新投递）
    }
}
```

**消费结果控制**：

```java
@Override
public void onMessage(String message) {
    try {
        process(message);
        // 不抛异常 = ACK，消息处理完成
    } catch (IllegalArgumentException e) {
        // 业务校验失败：不重试，直接记日志丢弃
        log.warn("消息格式非法，跳过: {}", e.getMessage());
    } catch (Exception e) {
        // 基础设施异常（DB、Redis）：抛出触发重试
        throw new RuntimeException(e);
    }
}
```

---

## 四、Nacos 配置

在 `realman-iot.yaml` 中添加（联系达尔文方获取 NameServer 地址和凭据）：

```yaml
rocketmq:
  name-server: ${ROCKETMQ_NAME_SERVER:达尔文NameServer地址:9876}
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
    enabled: ${DARWIN_INTEGRATION_ENABLED:false}   # 改为 true 才激活全部 Bean
    file-upload:
      upload-url-prefix: https://你的域名/realman-iot
      upload-bucket: darwin-files
      max-file-size-mb: 50
      url-expire-days: 7
```

> `darwin.integration.enabled=false`（默认）时，所有 Darwin Bean 均不创建，RocketMQ 连接也不会建立，不影响现有功能。

---

## 五、关键注意事项

### 1. 幂等处理是必须的

RocketMQ 保证"至少一次"投递，网络抖动时消息可能重复。本项目的两种做法：

```java
// 方式 A：DB 唯一键（工单消费）
Long count = mappingMapper.selectCount(
    eq(DarwinWorkOrderMapping::getDarwinOrderId, dto.getDarwinOrderId()));
if (count > 0) return; // 已处理，跳过

// 方式 B：Redis SET NX（文件上报消费）
Boolean isNew = redisTemplate.opsForValue()
    .setIfAbsent("darwin:file:report:" + id, "1", 24, TimeUnit.HOURS);
if (Boolean.FALSE.equals(isNew)) return; // 已处理，跳过
```

### 2. 区分业务异常与基础设施异常

| 异常类型 | 处理方式 | 原因 |
|---|---|---|
| 消息格式错误、数据不存在等业务校验失败 | 记日志，**不抛异常** | 重试没有意义，最终进死信队列浪费资源 |
| DB 超时、Redis 连接失败等基础设施异常 | **抛 RuntimeException** | 等待基础设施恢复后重试有意义 |

不区分会导致：格式错的消息被无效重试 16 次，消耗大量资源后才进死信队列。

### 3. 发送失败不能阻断主流程

Darwin 推送是附加功能，不能影响设备上下线等核心流程。Producer 内部已做 `try-catch`，发送失败只记 WARN 日志：

```java
// DarwinDeviceStatusProducer 的处理方式
} catch (Exception e) {
    log.warn("[Darwin] 设备状态推送失败 deviceCode={}", deviceCode, e);
    // 不向上抛，不影响 DeviceOnlineOfflineHandler 的主流程
}
```

### 4. 消息体统一用 JSON 字符串

使用 `RocketMQListener<String>`，自行用 `ObjectMapper` 反序列化，不要用泛型方式（`RocketMQListener<YourDTO>`），序列化行为不透明，排查问题困难。

### 5. Topic 需提前创建

RocketMQ 5.x 生产环境默认禁止自动创建 Topic，需联系达尔文方运维提前创建以下 5 个 Topic：

```
DARWIN_DEVICE_STATUS
DARWIN_WORKORDER_IN
DARWIN_OSS_AUTH_REQUEST
DARWIN_OSS_AUTH_RESPONSE
DARWIN_FILE_REPORT
```

### 6. 死信队列需要监控

消息重试超过 16 次后进入死信队列 `%DLQ%REALMAN_IOT_CONSUMER_GROUP`，说明出现了持续性故障。建议在 RocketMQ Dashboard 或 Grafana 对死信队列消息数配置告警。

---

## 六、本项目 Topic 一览

| Topic | 方向 | Tag | 消费/生产类 |
|---|---|---|---|
| `DARWIN_DEVICE_STATUS` | 推出（realman → Darwin） | `ONLINE` / `OFFLINE` | `DarwinDeviceStatusProducer` |
| `DARWIN_WORKORDER_IN` | 接收（Darwin → realman） | `CREATE` | `DarwinWorkOrderConsumer` |
| `DARWIN_OSS_AUTH_REQUEST` | 接收（Darwin → realman） | `REQUEST` | `DarwinOssAuthRequestConsumer` |
| `DARWIN_OSS_AUTH_RESPONSE` | 推出（realman → Darwin） | `RESPONSE` | `DarwinOssAuthResponseProducer` |
| `DARWIN_FILE_REPORT` | 接收（Darwin → realman） | `UPLOAD` | `DarwinFileReportConsumer` |

---

## 七、开发调试步骤

1. 启动 RocketMQ（`docker compose` 中 **`apache/rocketmq:5.3.2`**（与父 pom `rocketmq-broker-docker.version` 一致），或连达尔文测试环境）
2. 在 Nacos 设置 `darwin.integration.enabled=true` 并填写 `rocketmq.name-server`
3. 打开 RocketMQ Dashboard（`localhost:18088`）
4. 在 Dashboard「消息发送」手动向 `DARWIN_WORKORDER_IN` 发一条 JSON：

```json
{
  "traceId": "test-001",
  "darwinOrderId": "DW-TEST-001",
  "darwinAgentId": "A001",
  "darwinAgentName": "测试人员",
  "darwinDeptId": "D001",
  "darwinDeptName": "测试部",
  "taskName": "测试工单",
  "planStartTime": "2026-05-01T09:00:00",
  "planEndTime": "2026-05-01T18:00:00",
  "deviceCodes": ["RM-001"],
  "unitPrice": 500.00,
  "remark": "联调测试"
}
```

5. 观察 `DarwinWorkOrderConsumer` 日志是否打印 `工单创建成功`
6. 查询 DB `darwin_workorder_mapping` 表是否有新记录
7. 查询 `work_order` 表，`source=2` 的工单是否创建成功
