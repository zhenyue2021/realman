package org.jeecg.modules.device;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试专用启动类，仅用于为 @WebMvcTest 提供 @SpringBootConfiguration 上下文。
 * 放置在 org.jeecg.modules.device 包下，使测试类可向上扫描到此配置。
 */
@SpringBootApplication
public class TestDeviceApplication {
}
