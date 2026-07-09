package org.jeecg.modules.devicemgmt;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 设备管理业务平台启动类。
 *
 * <p>对齐 docs/design/2026-07-08-device-foundation-detailed-design.md 第三章：注册、
 * 双凭证体系（deviceSecret + Device Token）、ACL 规则；对外面向运维人员的台账/审计/
 * 绑定管理 REST 留给后续补充。
 *
 * <p>{@code @EnableFeignClients} 扫描设备信息基础服务契约包（用于注册成功后写入 SSOT）
 * 和 OTA 平台契约包（取消测试标记前置校验：查询设备是否存在进行中的 high_risk 任务）。
 * 与 {@code realman-boot-device-info} 服务一样不排除 {@code MybatisPlusSaasConfig}，
 * 复用其分页/乐观锁拦截器，不重复声明 {@code @MapperScan}。
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {
        "org.jeecg.modules.deviceinfo.contract.api",
        "org.jeecg.modules.ota.contract.api"
})
@SpringBootApplication(exclude = {DynamicDataSourceAutoConfiguration.class})
@ComponentScan(basePackages = "org.jeecg")
public class RealmanDeviceMgmtApplication {

    public static void main(String[] args) {
        ConfigurableEnvironment environment = SpringApplication.run(RealmanDeviceMgmtApplication.class, args).getEnvironment();
        printStartupInfo(environment);
    }

    private static void printStartupInfo(Environment env) {
        String serverPort = env.getProperty("server.port", "8091");
        String contextPath = env.getProperty("server.servlet.context-path", "/realman-device-mgmt");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // ignore
        }

        System.out.println("\n" +
                "╔════════════════════════════════════════════════════════════════════╗\n" +
                "║             Device Management Business Platform Started              ║\n" +
                "╠════════════════════════════════════════════════════════════════════╣\n" +
                "║  Local:   http://localhost:" + serverPort + contextPath + "\n" +
                "║  External:http://" + hostAddress + ":" + serverPort + contextPath + "\n" +
                "╚════════════════════════════════════════════════════════════════════╝");
    }
}
