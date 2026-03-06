package org.jeecg.modules.device;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

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
public class DeviceManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceManagementApplication.class, args);
        System.out.println("\n" +
            "╔══════════════════════════════════════════════════════╗\n" +
            "║     IoT Device Management Module Started            ║\n" +
            "║     http://localhost:8085/device-mgmt               ║\n" +
            "║     Swagger UI: /device-mgmt/swagger-ui/index.html  ║\n" +
            "╚══════════════════════════════════════════════════════╝");
    }
}
