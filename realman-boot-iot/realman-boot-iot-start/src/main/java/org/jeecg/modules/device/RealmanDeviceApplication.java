package org.jeecg.modules.device;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IoT设备管理模块启动类
 *
 * 鉴权架构：
 *   设备不需要登录，通过deviceSecret作为MQTT密码直连EMQX
 *   EMQX HTTP Auth插件回调 /internal/mqtt/auth 完成连接层鉴权
 *   Payload使用per-device AES-256-CBC加密（由deviceSecret派生密钥）
 *
 * 模块结构：
 *   device-api   - REST对外接口层（Controller/DTO/VO）
 *   device-biz   - 业务实现层（MQTT/OTA/Security/WebSocket/Scheduler）
 *   device-start - 主启动类 + 配置文件
 */
@SpringBootApplication(scanBasePackages = "org.jeecg.modules.device")
@MapperScan("org.jeecg.modules.device.mapper")
@EnableAsync
@EnableScheduling
public class RealmanDeviceApplication {
    public static void main(String[] args) {
        ConfigurableEnvironment environment = SpringApplication.run(RealmanDeviceApplication.class, args).getEnvironment();
        printStartupInfo(environment);
    }


    private static void printStartupInfo(Environment env) {
        String protocol = "http";
        String serverPort = env.getProperty("server.port", "8085");
        String contextPath = env.getProperty("server.servlet.context-path", "/realman-iot");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // ignore
        }

        System.out.println("\n" +
                "╔════════════════════════════════════════════════════════════════════╗\n" +
                "║                IoT Device Management Module Started                ║\n" +
                "╠════════════════════════════════════════════════════════════════════╣\n" +
                "║  Local:   " + protocol + "://localhost:" + serverPort + contextPath + "                  \n" +
                "║  External:" + protocol + "://" + hostAddress + ":" + serverPort + contextPath + "      \n" +
                "║  Swagger: " + protocol + "://localhost:" + serverPort + contextPath + "/swagger-ui/index.html  \n" +
                "╚════════════════════════════════════════════════════════════════════╝");
    }
}
