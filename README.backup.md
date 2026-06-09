RealmanBoot 低代码开发平台
===============

当前最新版本： 1.0.0（发布日期： 2026-03-02） 


[![AUR](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg)](https://github.com/zhangdaiscott/jeecg-boot/blob/master/LICENSE)
[![](https://img.shields.io/badge/Author-睿尔曼智能科技-orange.svg)](http://jeecg.com/aboutusIndex)
[![](https://img.shields.io/badge/version-1.0.0-brightgreen.svg)](https://github.com/zhangdaiscott/jeecg-boot)
[![GitHub stars](https://img.shields.io/github/stars/zhangdaiscott/jeecg-boot.svg?style=social&label=Stars)](https://github.com/zhangdaiscott/jeecg-boot)
[![GitHub forks](https://img.shields.io/github/forks/zhangdaiscott/jeecg-boot.svg?style=social&label=Fork)](https://github.com/zhangdaiscott/jeecg-boot)



项目介绍
-----------------------------------

<h3 align="center">系统整体架构设计</h3>

系统采用分层架构，从上到下分为五层：

`客户端层：`Web 管理平台、遥操平台移动端、IoT 设备端。

`API 网关层：`Spring Cloud Gateway 负责请求路由、限流、认证、日志记录。

`应用服务层：`六个核心微服务模块，分别为权限服务、设备服务、订阅服务、工单服务、远程支持服务、支付服务。

`中间件层：`EMQX MQTT Broker、Redis 缓存、RabbitMQ 消息队列、XXL-JOB 定时任务、WebSocket 实时推送、WebRTC 音视频通话。

`数据存储层：`MySQL 关系数据库、MinIO 文件存储、Elasticsearch 日志存储。

<h3 align="center">核心业务模块</h3>
`权限控制模块：`基于 Spring Security + JWT 的用户认证授权系统，支持用户管理、角色管理、权限管理、菜单权限、单点登录（SSO）和多租户隔离。

`设备管理模块：`完整的设备生命周期管理，包括设备注册、设备授权（租户/用户级别）、设备状态实时上报、在线设备数据统计、设备命令下发等功能。通过 EMQX MQTT Broker 实现云端与设备端的双向通信。

`订阅管理模块：`灵活的订阅方案管理和订单管理，支持多种计费周期、功能特性配置。订单生成后自动同步至钉钉，支持续期提醒和自动续期功能。

`工单管理模块：`完整的工单生命周期管理，支持工单创建、分配、进行中、待验收、已完成等状态流转。提供工单合规配置（验收策略、超时策略）、超时告警、工单数据统计等功能。

`远程支持模块：`遥操平台与管理平台之间的实时音视频通话，基于 WebRTC 实现 P2P 连接。支持通话录制、语音转写（Whisper）、AI 摘要提取（千问）、通话质量监控等功能。

`支付系统模块：`支持多种支付方式（支付宝、微信、Stripe），包括支付订单管理、支付回调处理、退款管理、支付数据统计等功能。


#### 项目说明

| 项目名              | 说明                                 | 
|------------------|------------------------------------|
| `realman-boot`   | 后端源码JAVA（SpringBoot3微服务架构）         |
| `realman-boot-vue3` | 前端源码VUE3（vue3+vite6+antd4+ts最新技术栈） |



启动项目
-----------------------------------

> 默认账号密码： admin/123456

- [开发环境搭建](https://help.jeecg.com/java/setup/tools)
- [IDEA启动前后端(单体模式)](https://help.jeecg.com/java/setup/idea/startup)
- [Docker一键启动(单体模式)](https://help.jeecg.com/java/docker/quick)
- [IDEA启动前后端(微服务方式)](https://help.jeecg.com/java/springcloud/switchcloud/monomer)
- [Docker一键启动(微服务方式)](https://help.jeecg.com/java/docker/quickcloud)


技术文档
-----------------------------------

- 官方网站：  [http://www.jeecg.com](http://www.jeecg.com)
- 在线演示：  [平台演示](https://boot3.jeecg.com) | [APP演示](https://jeecg.com/appIndex)
- 入门指南：  [快速入门](http://www.jeecg.com/doc/quickstart)  | [代码生成使用](https://help.jeecg.com/java/codegen/online) | [开发文档](https://help.jeecg.com)  | [AI应用手册](https://help.jeecg.com/aigc) | [视频教程](http://jeecg.com/doc/video)
- 技术支持：  [反馈问题](https://github.com/jeecgboot/JeecgBoot/issues/new?template=bug_report.md)    | [低代码体验一分钟](https://jeecg.blog.csdn.net/article/details/106079007)
- QQ交流群 ： 964611995、⑩716488839(满)、⑨808791225(满)、其他(满)




功能支持
-----------------------------------
- 1.采用最新主流前后分离框架（Spring Boot3 + MyBatis + Shiro/SpringAuthorizationServer + Ant Design4 + Vue3），容易上手；代码生成器依赖性低，灵活的扩展能力，可快速实现二次开发。
- 2.前端大版本换代，最新版采用 Vue3.0 + TypeScript + Vite6 + Ant Design Vue4 等新技术方案。
- 3.支持微服务Spring Cloud Alibaba（Nacos、Gateway、Sentinel、Skywalking），提供简易机制，支持单体和微服务自由切换（这样可以满足各类项目需求）。
- 4.开发效率高，支持在线建表和AI建表，提供强大代码生成器，单表、树列表、一对多、一对一等数据模型，增删改查功能一键生成，菜单配置直接使用。
- 5.代码生成器提供强大模板机制，支持自定义模板，目前提供四套风格模板（单表两套、树模型一套、一对多三套）。
- 6.提供强大的报表和大屏可视化工具，支持丰富的数据源连接，能够通过拖拉拽方式快速制作报表、大屏和门户设计；支持多种图表类型：柱形图、折线图、散点图、饼图、环形图、面积图、漏斗图、进度图、仪表盘、雷达图、地图等。
- 7.低代码能力：在线表单（无需编码，通过在线配置表单，实现表单的增删改查，支持单表、树、一对多、一对一等模型，实现人人皆可编码），在线配置零代码开发、所见即所得支持23种类控件。
- 8.低代码能力：在线报表、在线图表（无需编码，通过在线配置方式，实现数据报表和图形报表，可以快速抽取数据，减轻开发压力，实现人人皆可编码）。
- 9.Online支持在线增强开发，提供在线代码编辑器，支持代码高亮、代码提示等功能，支持多种语言（Java、SQL、JavaScript等）。
- 10.封装完善的用户、角色、菜单、组织机构、数据字典、在线定时任务等基础功能，支持访问授权、按钮权限、数据权限等功能。
- 11.前端UI提供丰富的组件库，支持各种常用组件，如表格、树形控件、下拉框、日期选择器等，满足各种复杂的业务需求 [UI组件库文档](https://help.jeecg.com/category/ui%E7%BB%84%E4%BB%B6%E5%BA%93)。
- 12.提供APP配套框架，一份多代码多终端适配，一份代码多终端适配，小程序、H5、安卓、iOS、鸿蒙Next。
- 13.新版APP框架采用Uniapp、Vue3.0、Vite、Wot-design-uni、TypeScript等最新技术栈，包括二次封装组件、路由拦截、请求拦截等功能。实现了与JeecgBoot完美对接：目前已经实现登录、用户信息、通讯录、公告、移动首页、九宫格、聊天、Online表单、仪表盘等功能，提供了丰富的组件。
- 14.提供了一套成熟的AI应用平台功能，从AI模型、知识库到AI应用搭建，助力企业快速落地AI服务，加速智能化升级。
- 15.AI能力：目前JeecgBoot支持AI大模型chatgpt和deepseek，现在最新版默认使用deepseek，速度更快质量更高。目前提供了AI对话助手、AI知识库、AI应用、AI建表、AI报表等功能。
- 16.提供新行编辑表格JVXETable，轻松满足各种复杂ERP布局，拥有更高的性能、更灵活的扩展、更强大的功能。
- 17.平台首页风格，提供多种组合模式，支持自定义风格；支持门户设计，支持自定义首页。
- 18.常用共通封装，各种工具类（定时任务、短信接口、邮件发送、Excel导入导出等），基本满足80%项目需求。
- 19.简易Excel导入导出，支持单表导出和一对多表模式导出，生成的代码自带导入导出功能。
- 20.集成智能报表工具，报表打印、图像报表和数据导出非常方便，可极其方便地生成PDF、Excel、Word等报表。
- 21.采用前后分离技术，页面UI风格精美，针对常用组件做了封装：时间、行表格控件、截取显示控件、报表组件、编辑器等。
- 22.查询过滤器：查询功能自动生成，后台动态拼SQL追加查询条件；支持多种匹配方式（全匹配/模糊查询/包含查询/不匹配查询）。
- 23.数据权限（精细化数据权限控制，控制到行级、列表级、表单字段级，实现不同人看不同数据，不同人对同一个页面操作不同字段）。
- 24.接口安全机制，可细化控制接口授权，非常简便实现不同客户端只看自己数据等控制；也提供了基于AK和SK认证鉴权的OpenAPI功能。
- 25.活跃的社区支持；近年来，随着网络威胁的日益增加，团队在安全和漏洞管理方面积累了丰富的经验，能够为企业提供全面的安全解决方案。
- 26.权限控制采用RBAC（Role-Based Access Control，基于角色的访问控制）。
- 27.页面校验自动生成（必须输入、数字校验、金额校验、时间空间等）。
- 28.支持SaaS服务模式，提供SaaS多租户架构方案。
- 29.分布式文件服务，集成MinIO、阿里OSS等优秀的第三方，提供便捷的文件上传与管理，同时也支持本地存储。
- 30.主流数据库兼容，一套代码完全兼容MySQL、PostgreSQL、Oracle、SQL Server、MariaDB、达梦、人大金仓等主流数据库。
- 31.集成工作流Flowable，并实现了只需在页面配置流程转向，可极大简化BPM工作流的开发；用BPM的流程设计器画出了流程走向，一个工作流基本就完成了，只需写很少量的Java代码。
- 32.低代码能力：在线流程设计，采用开源Flowable流程引擎，实现在线画流程、自定义表单、表单挂靠、业务流转。
- 33.多数据源：极其简易的使用方式，在线配置数据源配置，便捷地从其他数据抓取数据。
- 34.提供单点登录CAS集成方案，项目中已经提供完善的对接代码。
- 35.低代码能力：表单设计器，支持用户自定义表单布局，支持单表、一对多表单，支持select、radio、checkbox、textarea、date、popup、列表、宏等控件。
- 36.专业接口对接机制，统一采用RESTful接口方式，集成Swagger-UI在线接口文档，JWT token安全验证，方便客户端对接。
- 37.高级组合查询功能，在线配置支持主子表关联查询，可保存查询历史。
- 38.提供各种系统监控，实时跟踪系统运行情况（监控Redis、Tomcat、JVM、服务器信息、请求追踪、SQL监控）。
- 39.消息中心（支持短信、邮件、微信推送等）；集成WebSocket消息通知机制。
- 40.支持多语言，提供国际化方案。
- 41.数据变更记录日志，可记录数据每次变更内容，通过版本对比功能查看历史变化。
- 42.提供简单易用的打印插件，支持谷歌、火狐、IE11+等各种浏览器。
- 43.后端采用Maven分模块开发方式；前端支持菜单动态路由。
- 44.提供丰富的示例代码，涵盖了常用的业务场景，便于学习和参考。


技术架构：
-----------------------------------

#### 后端

- IDE建议： IDEA (必须安装lombok插件 )
- 语言：Java 默认jdk17(jdk21、jdk24)
- 依赖管理：Maven
- 基础框架：Spring Boot 3.5.5
- 微服务框架： Spring Cloud Alibaba 2023.0.3.3
- 持久层框架：MybatisPlus 3.5.12
- 报表工具： JimuReport 2.1.3
- 安全框架：Apache Shiro 2.0.4，Jwt 4.5.0
- 微服务技术栈：Spring Cloud Alibaba、Nacos、Gateway、Sentinel、Skywalking
- 数据库连接池：阿里巴巴Druid 1.2.24
- 消息中间件：RocketMQ 4.9.4, Mqtt 3.1.8, WebSocket 
- AI大模型：支持 `ChatGPT` `DeepSeek` `千问`等各种常规模式
- 日志打印：logback
- 缓存：Redis
- 其他：autopoi, fastjson，poi，Swagger-ui，quartz, lombok（简化代码）等。
- 默认提供MySQL5.7+数据库脚本
- [其他数据库，需要自己转](https://my.oschina.net/jeecg/blog/4905722)


#### 前端

- 前端环境要求：Node.js要求`Node 20+` 版本以上、pnpm 要求`9+` 版本以上
  ` ( Vite 不再支持已结束生命周期（EOL）的 Node.js 18。现在需要使用 Node.js 20.19+ 或 22.12+)`

- 依赖管理：node、npm、pnpm
- 前端IDE建议：IDEA、WebStorm、Vscode
- 采用 Vue3.0+TypeScript+Vite6+Ant-Design-Vue4等新技术方案，包括二次封装组件、utils、hooks、动态菜单、权限校验、按钮级别权限控制等功能
- 最新技术栈：Vue3.0 + TypeScript + Vite6 + ant-design-vue4 + pinia + echarts + unocss + vxe-table + qiankun + es6




#### 支持库

|  数据库   |  支持   |
| --- | --- |
|   MySQL   |  √   |
|  Oracle11g   |  √   |
|  Sqlserver2017   |  √   |
|   PostgreSQL   |  √   |
|   MariaDB   |  √   |
|   达梦   |  √   |
|   人大金仓   |  √   |
|   TiDB   |  √   |



 
## 微服务解决方案


- 1、服务注册和发现 Nacos √
- 2、统一配置中心 Nacos  √
- 3、路由网关 gateway(三种加载方式) √
- 4、分布式 http feign √
- 5、熔断降级限流 Sentinel √
- 6、分布式文件 Minio、阿里OSS √ 
- 7、统一权限控制 JWT + Shiro √
- 8、服务监控 SpringBootAdmin√
- 9、链路跟踪 Skywalking   [参考文档](https://help.jeecg.com/java/springcloud/super/skywarking)
- 10、消息中间件 RabbitMQ  √
- 11、分布式任务 xxl-job  √ 
- 12、分布式事务 Seata
- 13、轻量分布式日志 Loki+grafana套件
- 14、支持 docker-compose、k8s、jenkins
- 15、CAS 单点登录   √
- 16、路由限流   √



后台目录结构
-----------------------------------
```
项目结构
├─jeecg-boot-parent（父POM： 项目依赖、modules组织）
│  ├─jeecg-boot-base-core（共通模块： 工具类、config、权限、查询过滤器、注解等）
│  ├─jeecg-module-demo    示例代码
│  ├─jeecg-module-system  System系统管理目录
│  │  ├─jeecg-system-biz    System系统管理权限等功能
│  │  ├─jeecg-system-start  System单体启动项目(8080）
│  │  ├─jeecg-system-api    System系统管理模块对外api
│  │  │  ├─jeecg-system-cloud-api   System模块对外提供的微服务接口
│  │  │  ├─jeecg-system-local-api   System模块对外提供的单体接口
│  ├─realman-server-cloud           --微服务模块
     ├─realman-cloud-gateway       --微服务网关模块(9999)
     ├─realman-cloud-nacos       --Nacos服务模块(8848)
     ├─realman-system-cloud-start  --System微服务启动项目(7001)
     ├─jeecg-demo-cloud-start    --Demo微服务启动项目(7002)
     ├─realman-visual
        ├─realman-cloud-monitor       --微服务监控模块 (9111)
        ├─realman-cloud-xxljob        --微服务xxljob定时任务服务端 (9080)
        ├─realman-cloud-sentinel     --sentinel服务端 (9000)
        ├─jeecg-cloud-test           -- 微服务测试示例（各种例子）
           ├─jeecg-cloud-test-more         -- 微服务测试示例（feign、熔断降级、xxljob、分布式锁）
           ├─jeecg-cloud-test-rabbitmq     -- 微服务测试示例（rabbitmq）
           ├─jeecg-cloud-test-seata          -- 微服务测试示例（seata分布式事务）
           ├─jeecg-cloud-test-shardingsphere    -- 微服务测试示例（分库分表）
```




#### 微服务架构图

![微服务架构图](/system-architecture.png "在这里输入图片标题")







