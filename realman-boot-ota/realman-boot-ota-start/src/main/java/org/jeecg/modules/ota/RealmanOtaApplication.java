package org.jeecg.modules.ota;

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
 * OTA 平台启动类。对齐 docs/design/2026-07-09-ota-platform-detailed-design.md。
 *
 * <p>{@code @EnableFeignClients} 同时扫描设备信息基础服务（设备/资源查询）、
 * 设备管理业务平台（is_test_device 只读）、设备通信中台（统一下行发布 + 上行事件
 * 轮询）契约包。与其余 V2 新服务一样不排除 {@code MybatisPlusSaasConfig}。
 */
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = {
        "org.jeecg.modules.deviceinfo.contract.api",
        "org.jeecg.modules.devicemgmt.contract.api",
        "org.jeecg.modules.commhub.contract.api"
})
@SpringBootApplication(exclude = {DynamicDataSourceAutoConfiguration.class})
@ComponentScan(basePackages = "org.jeecg")
public class RealmanOtaApplication {

    public static void main(String[] args) {
        ConfigurableEnvironment environment = SpringApplication.run(RealmanOtaApplication.class, args).getEnvironment();
        printStartupInfo(environment);
    }

    private static void printStartupInfo(Environment env) {
        String serverPort = env.getProperty("server.port", "8093");
        String contextPath = env.getProperty("server.servlet.context-path", "/realman-ota");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // ignore
        }

        System.out.println("\n" +
                "╔════════════════════════════════════════════════════════════════════╗\n" +
                "║                       OTA Platform Started                            ║\n" +
                "╠════════════════════════════════════════════════════════════════════╣\n" +
                "║  Local:   http://localhost:" + serverPort + contextPath + "\n" +
                "║  External:http://" + hostAddress + ":" + serverPort + contextPath + "\n" +
                "╚════════════════════════════════════════════════════════════════════╝");
    }
}
