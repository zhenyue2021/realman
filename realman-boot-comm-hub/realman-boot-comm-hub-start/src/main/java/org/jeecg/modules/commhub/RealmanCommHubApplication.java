package org.jeecg.modules.commhub;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 设备通信中台启动类。
 *
 * <p>对齐 docs/design/2026-07-08-device-comm-hub-detailed-design.md：独立 MQTT 客户端
 * 连接 EMQX（南向唯一协议，注册除外）+ EMQX 鉴权/ACL 回调 + 统一下行发布
 * （含 publish-and-wait）+ 上行事件归一化/Webhook 转发 + 自注册转发。
 *
 * <p>{@code @EnableFeignClients} 同时扫描设备管理业务平台（自注册转发目标）与
 * 设备信息基础服务（在线/离线/心跳事件同步目标）契约包。与
 * {@code realman-boot-device-info}/{@code realman-boot-device-mgmt} 一样不排除
 * {@code MybatisPlusSaasConfig}，复用其分页/乐观锁拦截器，不重复声明 {@code @MapperScan}。
 *
 * <p>{@code @EnableScheduling}：Topic 路由注册表定时刷新（{@code CommHubTopicRouteRegistry}）。
 */
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = {
        "org.jeecg.modules.devicemgmt.contract.api",
        "org.jeecg.modules.deviceinfo.contract.api"
})
@SpringBootApplication(exclude = {DynamicDataSourceAutoConfiguration.class})
@ComponentScan(basePackages = "org.jeecg")
public class RealmanCommHubApplication {

    public static void main(String[] args) {
        ConfigurableEnvironment environment = SpringApplication.run(RealmanCommHubApplication.class, args).getEnvironment();
        printStartupInfo(environment);
    }

    private static void printStartupInfo(Environment env) {
        String serverPort = env.getProperty("server.port", "8092");
        String contextPath = env.getProperty("server.servlet.context-path", "/realman-comm-hub");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // ignore
        }

        System.out.println("\n" +
                "╔════════════════════════════════════════════════════════════════════╗\n" +
                "║                  Device Comm Hub Started                             ║\n" +
                "╠════════════════════════════════════════════════════════════════════╣\n" +
                "║  Local:   http://localhost:" + serverPort + contextPath + "\n" +
                "║  External:http://" + hostAddress + ":" + serverPort + contextPath + "\n" +
                "╚════════════════════════════════════════════════════════════════════╝");
    }
}
