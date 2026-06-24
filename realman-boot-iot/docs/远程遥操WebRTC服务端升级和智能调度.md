# 远程遥操WebRTC服务端升级和智能调度

# 1、背景介绍

       基于WebRTC的远程遥操（[《基于WebRTC的机器人遥操作服务端方案》](https://alidocs.dingtalk.com/i/nodes/oP0MALyR8kRvgalNIQGYrvApJ3bzYmDO?utm_scene=team_space)）仅仅实现了媒体服务器（Turn和信令服务）的基本功能，对于业务监控、服务器集群化部署，以及关键流量的智能调度策略，还需要进一步设计和开发。

# 2、Turn服务器升级

## 2.1、TURN服务房间隔离

### 概述

        在多个机器人和浏览器之间进行视频传输的过程中，每一个机器人和对应的浏览器组成一个房间，为了区分不同房间的日志，需要对相关模块的使用进行改造。

        通过配置TURN服务器的`user-realm-separator`参数，让服务器从客户端用户名中自动解析出`realm`（房间标识），从而区分不同房间的会话流量。此方案无需数据库，配置简单，适用于小规模部署

### 架构图

![/Users/cccc/Downloads/deepseek_mermaid_20260519_0aacb3.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/3M0OzeZay4joQqze/img/a3a099ef-aa48-4cab-a505-a06358c033c8.png)

### ICE认证流程

![/Users/cccc/Downloads/deepseek_mermaid_20260519_69cb85.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/3M0OzeZay4joQqze/img/e162f11b-3437-4c0d-afc4-fa19c2794cef.png)

### 配置步骤

1.  TURN服务器配置
    
          编辑turnserver.conf：
    
    #启用用户名解析，以下划线为分隔符
    
          user-realm-separator=\_
    
    #认证方式
    
          lt-cred-mech
    
    #统一用户（密码所有房间共用）
    
          user=realman:TBdWj0HhwTK56AzK
    
2.  重启TURN服务
    
    dockerrestartcoturn
    
3.  客户端配置
    

*   机器人端(webrtc\_sender)
    

```plaintext
{
  "turnServers": [
    {
      "url": "turn:47.93.156.69:3479?transport=udp",
      "username": "room123_realman",
      "password": "TBdWj0HhwTK56AzK"
    }
  ]
}
```

*   浏览器端
    

```plaintext
const configuration = {
  iceServers: [{
    urls: 'turn:47.93.156.69:3479?transport=udp',
    username: 'room123_realman',
    credential: 'TBdWj0HhwTK56AzK'
  }]
};
```

*   日志示例
    

房间A(room\_123)的会话

       session001:realm=,username=,rp=1767,rb=1719549

房间B(room\_456)的会话

       session002:realm=,username=,rp=500,rb=40000

## 2.2、TURN服务抗弱网性能提升

### 概述

        针对机器人遥操场景中**上行（机器人→TURN服务器→浏览器）和下行（浏览器→TURN服务器→机器人）网络条件不对称**的问题，在TURN服务器上实现分段QoS策略，分别处理两端的丢包和延迟，优化整体WebRTC传输质量。

        举个实际当中的例子：浏览器在北京，机器人在上海，Turn服务器部署在北京。视频从上海的机器人传到北京的Turn服务器，然后再到北京的浏览器。如果北京浏览器所在的网络不佳（最后一公里丢包），那么北京浏览器的WebRTC会向上海的机器人发送NACK请求，NACK请求所经历的网络链路是北京到上海的物理链路。如果能够在Turn服务器上缓存数据包，那么NACK请求就可以在北京的Turn服务器上找到需要重传的数据包，请求和响应的链路相比从上海请求远远缩短。

        如下图所示：

![/Users/cccc/Downloads/deepseek_mermaid_20260522_a17f94.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/3M0OzeZay4joQqze/img/41e9af95-85ce-4053-bf2d-d041f4df58a9.png)

# 3、Turn服务器的集群化部署和流量智能调度

**第一阶段：集中监控+静态路由（负载感知）**

这一阶段的核心逻辑是：**调度器知道“谁健康、谁在哪儿”，但决策规则是静态的。**

*   **数据采集**：调度服务器通过Prometheus监控所有Turn服务器的实时负载（连接数、CPU、内存）和健康状况。
    
*   **调度规则(静态)**：
    
    *   华北区域（北京）的用户，**优先匹配**华北区域的Turn集群。
        
    *   如果本地集群负载超过阈值（例如80%），才将新用户溢出调度到相邻较空闲的集群（例如华东）。
        
    *   **物理位置路由表是静态配置的**（例如：河南→华北区域）。
        
*   **动态部分**：节点上下线、负载高低是动态变化的，调度器能根据这些信息做出响应。
    

**需要注意**：这里“静态”的只是地理位置与调度区域的映射关系。一个典型的风险是，河南联通用户到北京节点（18ms）可能确实比到上海（32ms）快，但如果北京节点已经过载或出现网络抖动，调度器还是会将其“静态”地分配到北京，可能就会导致体验不佳。这就凸显了下一阶段引入动态路由的必要性。

**第二阶段：引入动态网络拓扑（全网智能路由）**

这一阶段引入的关键能力，是**“网络质量地图”的离线计算与动态绑定**。

*   **核心组件**：增加一个独立的**网络探测服务(ProbeService)**，在全国各主要区域（或与你的Turn集群同区域）部署轻量级探针。
    
*   **工作流程**:
    
    1.  **离线探测**：探针持续从各地向所有Turn集群发起模拟的STUN/TURN请求，测量**真实的RTT和丢包率**，生成一张“源地域->目标Turn集群”的质量矩阵表，存入数据库。
        
    2.  **动态路由表**：调度器的静态地理位置映射，升级为这张基于**实时测量数据生成的动态路由表**。例如，数据可能会显示“河南联通”到“北京节点”的实测延迟高于到“上海节点”，调度器就会自动选择上海，而不是死板地遵循物理距离最近的规则。
        
    3.  **房间亲和性**：确保同一个房间内的所有客户端，被调度到同一个最优的Turn节点，避免跨节点转发。
        
*   **风险**：探测行为会产生额外的网络和计算开销。你需要合理设置探测频率，避免对Turn集群造成不必要的压力。同时，如何处理网络瞬间剧烈波动（闪断、拥堵）也是提升系统鲁棒性的一个课题。
    

**第三阶段：Turn级联（跨区域/跨国）**

这一步主要用于解决超远距离（如中国与美国）或网络政策（如中国的“墙”）导致的直接连接质量不佳或无法连接的问题。原理是让多个Turn服务器形成一条中继链，例如：客户端A（中国）->Turn-北京->Turn-美西->客户端B（美国）。

*   **实现方式**：
    
    *   **协议支持**：这需要在Turn客户端和服务器层面进行开发，利用TURN协议的去向（Send/Data）指示或RPC方式，实现跨服务器的消息转发。Coturn是否原生支持级联，可能需要你进一步确认。
        
    *   **调度决策**：当调度器发现两个客户端的最优入口点不同（例如，最优路径不重叠或直连质量不佳）时，它会决定启用级联路径，并为客户端配置中继链路。
        
*   **风险**：这个模式的技术实现比较复杂，因为需要服务器之间进行通信。同时，额外的中继节点会成倍增加延迟，并且会产生跨地域或跨国的流量费用。
    

# 4、Turn服务器的调度细节

## 系统架构

![/Users/cccc/Downloads/deepseek_mermaid_20260621_ad620a.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/3M0OzeZay4joQqze/img/c069f186-c233-4db2-a7d1-590d7346ecd5.png)

## 时序图

![/Users/cccc/Downloads/deepseek_mermaid_20260621_4a38f0.png](https://alidocs.oss-cn-zhangjiakou.aliyuncs.com/res/3M0OzeZay4joQqze/img/900c3c13-b6f2-41dd-828e-4d7fca61cd92.png)

## 调用关系说明

| 步骤 | 方向 | 说明 |
| --- | --- | --- |
| ① | 业务服务器→turn\_router | 请求最佳TURN服务器（携带机器人/浏览器位置） |
| ② | turn\_router→Prometheus | 定时查询所有Coturn的负载指标（`turn_total_allocations`） |
| ③ | Prometheus→CoturnExporter | 拉取`/metrics`获取连接数、流量等数据 |
| ④ | turn\_router→业务服务器 | 返回最优TURN服务器（ID+IP+Port） |
| ⑤ | 业务服务器→机器人/主控端 | 通知使用哪个TURN服务器 |
| ⑥ | 机器人/主控端→Coturn | 通过TURN中继WebRTC媒体流 |

## 端口汇总

| 服务 | 端口 | 协议 | 用途 |
| --- | --- | --- | --- |
| Coturn信令 | 3479 | TCP/UDP | STUN/TURN |
| Coturn中继 | 50002-51000 | UDP | 媒体中继 |
| CoturnExporter | 9641 | TCP | Prometheus拉取指标 |
| Prometheus | 9090 | TCP | WebUI+API |
| turn\_router | 8081 | TCP | 调度API |
| network\_detector\_server | 50001 | UDP | 网络诊断（与Coturn中继端口分开） |

## 业务服务器查询接口

### 请求示例

bash

```plaintext
curl -X POST http://8.141.21.23:8081/api/v1/route/turn \
  -H "Content-Type: application/json" \
  -d '{
    "callId": "call_20260622_001",
    "robotProvince": "上海",
    "robotCity": "上海",
    "browserProvince": "北京",
    "browserCity": "北京"
  }'
```

### 请求参数说明

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `callId` | string | ✅是 | 通话唯一标识（用于日志追踪） |
| `robotProvince` | string | ✅是 | 机器人所在省份 |
| `robotCity` | string | ❌否 | 机器人所在城市（精细化调度） |
| `browserProvince` | string | ✅是 | 主控端所在省份 |
| `browserCity` | string | ❌否 | 主控端所在城市（精细化调度） |

### 返回结果示例

#### 成功响应

json

```plaintext
{
    "success": true,
    "serverId": "47.102.207.121",
    "serverIp": "47.102.207.121",
    "serverPort": 3479,
    "serverName": "上海TURN服务器1",
    "signalKey": "room-key-from-turn-router",
    "message": ""
}
```

#### 失败响应（无可用TURN服务器）

json

```plaintext
{
    "success": false,
    "serverId": "",
    "serverIp": "",
    "serverPort": 0,
    "serverName": "",
    "message": "No available TURN server"
}
```

### 返回字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | bool | 是否成功分配到TURN服务器 |
| `serverId` | string | TURN服务器唯一标识 |
| `serverIp` | string | TURN服务器公网IP |
| `serverPort` | int | TURN服务器端口（3479） |
| `serverName` | string | TURN服务器名称 |
| `signalKey` | string | 信令服务器房间密钥，业务侧原样下发给浏览器/机器人 |
| `message` | string | 错误信息（仅失败时返回） |

### 近期任务规划

1、业务服务器相关开发   ${@Lorete}$                                                                                                                完成时间6.24

2、Turn服务器的状态信息（负载情况、RTT等QoS）统计和上报 ${@Vincent}$                                               已完成

3、初步的调度服务，根据机器人和主控端上报的地理位置信息决定选择哪台Turn服务器 ${@Vincent}$         完成时间6.25

4、浏览器上报地理位置信息   ${@Cherish}$                                                                                                       完成时间6.24

5、阿里云服务部署完成    ${@Vincent}$                                                                                                              完成时间6.23

### 后续任务规划

1、Turn服务房间隔离    ${@Vincent}$                                                                                                                  完成时间6.25

2、Turn服务抗弱网性能提升   ${@Vincent}$                                                                                                        远期