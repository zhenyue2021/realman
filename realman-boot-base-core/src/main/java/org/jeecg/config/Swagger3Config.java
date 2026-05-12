package org.jeecg.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.constant.CommonConstant;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.filters.GlobalOpenApiMethodFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author eightmonth
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "knife4j", name = "production", havingValue = "false", matchIfMissing = true)
@PropertySource("classpath:config/default-spring-doc.properties")
public class Swagger3Config implements WebMvcConfigurer {

    // 路径匹配结果缓存，避免重复计算
    private static final Map<String, Boolean> EXCLUDED_PATHS_CACHE = new ConcurrentHashMap<>();
    // 定义不需要注入安全要求的路径集合
    private static final Set<String> excludedPaths = new HashSet<>(Arrays.asList(
            "/sys/randomImage/**",
            "/sys/login",
            "/sys/phoneLogin",
            "/sys/mLogin",
            "/sys/sms",
            "/sys/cas/client/validateLogin",
            "/test/jeecgDemo/demo3",
            "/sys/thirdLogin/**",
            "/sys/user/register"
    ));
    // 预处理通配符模式，提高匹配效率
    private static final Set<String> wildcardPatterns = new HashSet<>();
    private static final Set<String> exactPatterns = new HashSet<>();
    static {
        // 初始化时分离精确匹配和通配符匹配
        for (String pattern : excludedPaths) {
            if (pattern.endsWith("/**")) {
                wildcardPatterns.add(pattern.substring(0, pattern.length() - 3));
            } else {
                exactPatterns.add(pattern);
            }
        }
    }
    
    /**
     *
     * 显示swagger-ui.html文档展示页，还必须注入swagger资源：
     *
     * @param registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("swagger-ui.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    @Bean
    public GlobalOpenApiMethodFilter globalOpenApiMethodFilter() {
        return method -> method.isAnnotationPresent(Operation.class);
    }

    @Bean
    public OperationCustomizer operationCustomizer() {
        return (operation, handlerMethod) -> {
            String path = getFullPath(handlerMethod);
            if (!isExcludedPath(path)) {
                operation.addSecurityItem(new SecurityRequirement().addList(CommonConstant.X_ACCESS_TOKEN));
            }else{
                log.info("忽略加入 X_ACCESS_TOKEN 的 PATH:" + path);
            }
            return operation;
        };
    }


    /**
     * 按各接口上声明的 x-order 值对 Swagger 文档中的 Path 进行排序。
     *
     * 使用方式：在 Controller 方法的 @Operation 上添加扩展：
     * <pre>
     * {@code
     * @Operation(
     *     summary = "创建用户",
     *     extensions = @Extension(properties = {
     *         @ExtensionProperty(name = "x-order", value = "1")
     *     })
     * )
     * }
     * </pre>
     * 未标注 x-order 的接口统一排到末尾。
     */
    @Bean
    public OpenApiCustomizer operationOrderCustomizer() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null || paths.isEmpty()) {
                return;
            }

            // 收集每个 path 对应的最小 x-order 值（一个 path 下可能有多个 HTTP method）
            Map<String, Integer> pathOrderMap = new LinkedHashMap<>();
            paths.forEach((path, pathItem) -> {
                int minOrder = pathItem.readOperations().stream()
                        .mapToInt(op -> {
                            if (op.getExtensions() == null) {
                                return Integer.MAX_VALUE;
                            }
                            Object xOrder = op.getExtensions().get("x-order");
                            if (xOrder == null) {
                                return Integer.MAX_VALUE;
                            }
                            try {
                                return Integer.parseInt(xOrder.toString());
                            } catch (NumberFormatException e) {
                                log.warn("x-order 值非整数，path={}，value={}", path, xOrder);
                                return Integer.MAX_VALUE;
                            }
                        })
                        .min()
                        .orElse(Integer.MAX_VALUE);
                pathOrderMap.put(path, minOrder);
            });

            // 按 x-order 升序重建 Paths（未标注的排在最后，保留原始相对顺序）
            Paths sortedPaths = new Paths();
            pathOrderMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(entry -> sortedPaths.addPathItem(entry.getKey(), paths.get(entry.getKey())));

            openApi.setPaths(sortedPaths);
        };
    }

    private String getFullPath(HandlerMethod handlerMethod) {
        StringBuilder fullPath = new StringBuilder();

        // 获取类级别的路径
        RequestMapping classMapping = handlerMethod.getBeanType().getAnnotation(RequestMapping.class);
        if (classMapping != null && classMapping.value().length > 0) {
            fullPath.append(classMapping.value()[0]);
        }

        // 获取方法级别的路径
        RequestMapping methodMapping = handlerMethod.getMethodAnnotation(RequestMapping.class);
        if (methodMapping != null && methodMapping.value().length > 0) {
            String methodPath = methodMapping.value()[0];
            // 确保路径正确拼接，处理斜杠
            if (!fullPath.toString().endsWith("/") && !methodPath.startsWith("/")) {
                fullPath.append("/");
            }
            fullPath.append(methodPath);
        }

        return fullPath.toString();
    }


    private boolean isExcludedPath(String path) {
        // 使用缓存避免重复计算
        return EXCLUDED_PATHS_CACHE.computeIfAbsent(path, p -> {
            // 精确匹配
            if (exactPatterns.contains(p)) {
                return true;
            }
            // 通配符匹配
            return wildcardPatterns.stream().anyMatch(p::startsWith);
        });
    }
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RealmanBoot 后台服务API接口文档")
                        .version("1.0.0")
                        .contact(new Contact().name("睿尔曼智能科技(北京)有限公司").url("https://www.realman-robotics.cn/").email("jeecgos@163.com"))
                        .description("后台API接口")
                        .termsOfService("NO terms of service")
                        .license(new License().name("Apache 2.0").url("http://www.apache.org/licenses/LICENSE-2.0.html")))
                .addSecurityItem(new SecurityRequirement().addList(CommonConstant.X_ACCESS_TOKEN))
                .components(new Components().addSecuritySchemes(CommonConstant.X_ACCESS_TOKEN,
                        new SecurityScheme()
                                .name(CommonConstant.X_ACCESS_TOKEN)
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER) // 关键：指定为 header
                ));
    }
}