package org.jeecg.modules.deviceinfo;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 设备信息基础服务（SSOT）启动类。
 *
 * <p>对齐 docs/design/2026-07-08-device-foundation-detailed-design.md 第二章：只读为主，
 * 读多写少，供全平台 Feign 查询；写路径仅接受设备管理业务平台/设备通信中台/OTA 平台
 * 各自负责的字段子集，不做审计与权限校验（那是设备管理业务平台的职责）。
 *
 * <p>扫描范围沿用 realman-boot-iot 的既有做法，扩展到 org.jeecg 以复用
 * realman-boot-base-core 的公共能力（统一异常处理、Result 包装、MyBatis-Plus
 * 分页/乐观锁拦截器等）。与 IoT 模块不同，本服务不排除
 * {@code MybatisPlusSaasConfig}，直接复用其 {@code mybatisPlusInterceptor} Bean
 * （分页 + 乐观锁），故不再重复声明 {@code @MapperScan}——
 * {@code MybatisPlusSaasConfig} 的 {@code org.jeecg.**.mapper*} 扫描范围已覆盖
 * {@code org.jeecg.modules.deviceinfo.mapper}。
 */
@EnableDiscoveryClient
@SpringBootApplication(exclude = {DynamicDataSourceAutoConfiguration.class})
@ComponentScan(basePackages = "org.jeecg")
public class RealmanDeviceInfoApplication {

    public static void main(String[] args) {
        ConfigurableEnvironment environment = SpringApplication.run(RealmanDeviceInfoApplication.class, args).getEnvironment();
        printStartupInfo(environment);
    }

    private static void printStartupInfo(Environment env) {
        String serverPort = env.getProperty("server.port", "8090");
        String contextPath = env.getProperty("server.servlet.context-path", "/realman-device-info");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // ignore
        }

        System.out.println("\n" +
                "╔════════════════════════════════════════════════════════════════════╗\n" +
                "║           Device Info Foundation Service (SSOT) Started               ║\n" +
                "╠════════════════════════════════════════════════════════════════════╣\n" +
                "║  Local:   http://localhost:" + serverPort + contextPath + "\n" +
                "║  External:http://" + hostAddress + ":" + serverPort + contextPath + "\n" +
                "╚════════════════════════════════════════════════════════════════════╝");
    }
}
