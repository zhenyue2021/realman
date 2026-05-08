package org.jeecg.config.shiro;

import jakarta.annotation.Resource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.spring.web.ShiroUrlPathHelper;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.crazycake.shiro.*;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.JeecgBaseConfig;
import org.jeecg.config.shiro.filters.CustomShiroFilterFactoryBean;
import org.jeecg.config.shiro.filters.JwtFilter;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import java.util.*;

/**
 * @author: Scott
 * @date: 2018/2/7
 * @description: shiro 配置类
 */

@Slf4j
@Configuration
public class ShiroConfig {

    /**
     * Shiro FilterFactoryBean 在 Spring 容器中的 Bean 名称。
     * 同时用于 @Bean 声明和 DelegatingFilterProxyRegistrationBean 引用，
     * 保证两处始终一致，避免魔法字符串导致的运行时错误。
     */
    private static final String SHIRO_FILTER_BEAN_NAME = "shiroFilterFactoryBean";

    @Resource
    private LettuceConnectionFactory lettuceConnectionFactory;
    @Autowired
    private Environment env;
    @Resource
    private JeecgBaseConfig jeecgBaseConfig;
    @Autowired(required = false)
    private RedisProperties redisProperties;

    /**
     * Filter Chain定义说明
     * <p>
     * 1、一个URL可以配置多个Filter，使用逗号分隔
     * 2、当设置多个过滤器时，全部验证通过，才视为通过
     * 3、部分过滤器可指定参数，如perms，roles
     */
    @Bean(SHIRO_FILTER_BEAN_NAME)
    public ShiroFilterFactoryBean shiroFilter(SecurityManager securityManager) {
        CustomShiroFilterFactoryBean shiroFilterFactoryBean = new CustomShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        // 拦截器（LinkedHashMap 保证顺序，从上往下依次匹配，/** 兜底放最后）
        Map<String, String> filterChainDefinitionMap = new LinkedHashMap<>();

        // 支持 yml 方式配置拦截排除
        if (jeecgBaseConfig != null && jeecgBaseConfig.getShiro() != null) {
            String shiroExcludeUrls = jeecgBaseConfig.getShiro().getExcludeUrls();
            if (oConvertUtils.isNotEmpty(shiroExcludeUrls)) {
                for (String url : shiroExcludeUrls.split(",")) {
                    filterChainDefinitionMap.put(url, "anon");
                }
            }
        }

        // ---- 认证 / 登录相关 ----
        filterChainDefinitionMap.put("/sys/cas/client/validateLogin", "anon"); // CAS 验证登录
        filterChainDefinitionMap.put("/sys/randomImage/**", "anon");           // 登录验证码
        filterChainDefinitionMap.put("/sys/checkCaptcha", "anon");             // 登录验证码校验
        filterChainDefinitionMap.put("/sys/smsCheckCaptcha", "anon");          // 短信次数限制验证码
        filterChainDefinitionMap.put("/sys/login", "anon");                    // 登录接口
        filterChainDefinitionMap.put("/sys/mLogin", "anon");                   // 移动端登录
        filterChainDefinitionMap.put("/sys/logout", "anon");                   // 登出接口
        filterChainDefinitionMap.put("/sys/thirdLogin/**", "anon");            // 第三方登录
        filterChainDefinitionMap.put("/sys/getEncryptedString", "anon");       // 获取加密串
        filterChainDefinitionMap.put("/sys/sms", "anon");                      // 短信验证码
        filterChainDefinitionMap.put("/sys/phoneLogin", "anon");               // 手机号登录
        filterChainDefinitionMap.put("/sys/user/checkOnlyUser", "anon");       // 校验用户是否存在
        filterChainDefinitionMap.put("/sys/user/register", "anon");            // 用户注册
        filterChainDefinitionMap.put("/sys/user/phoneVerification", "anon");   // 忘记密码-手机号验证
        filterChainDefinitionMap.put("/sys/user/passwordChange", "anon");      // 忘记密码-修改密码
        filterChainDefinitionMap.put("/auth/2step-code", "anon");              // 两步验证码
        filterChainDefinitionMap.put("/sys/getLoginQrcode/**", "anon");        // 登录二维码
        filterChainDefinitionMap.put("/sys/getQrcodeToken/**", "anon");        // 监听扫码
        filterChainDefinitionMap.put("/sys/checkAuth", "anon");                // 授权接口
        filterChainDefinitionMap.put("/sys/version/app3version", "anon");      // App vue3 版本查询

        // ---- 文件 / 预览 ----
        filterChainDefinitionMap.put("/sys/common/static/**", "anon"); // 图片预览 & 文件下载
        filterChainDefinitionMap.put("/sys/common/pdf/**", "anon");    // PDF 预览
        filterChainDefinitionMap.put("/generic/**", "anon");           // PDF 预览依赖文件

        // ---- 静态资源（前端构建产物）----
        filterChainDefinitionMap.put("/", "anon");
        filterChainDefinitionMap.put("/doc.html", "anon");
        filterChainDefinitionMap.put("/**/*.js", "anon");
        filterChainDefinitionMap.put("/**/*.js.map", "anon");
        filterChainDefinitionMap.put("/**/*.css", "anon");
        filterChainDefinitionMap.put("/**/*.css.map", "anon");
        filterChainDefinitionMap.put("/**/*.html", "anon");
        filterChainDefinitionMap.put("/**/*.svg", "anon");
        filterChainDefinitionMap.put("/**/*.pdf", "anon");
        filterChainDefinitionMap.put("/**/*.jpg", "anon");
        filterChainDefinitionMap.put("/**/*.png", "anon");
        filterChainDefinitionMap.put("/**/*.gif", "anon");
        filterChainDefinitionMap.put("/**/*.ico", "anon");
        filterChainDefinitionMap.put("/**/*.ttf", "anon");
        filterChainDefinitionMap.put("/**/*.woff", "anon");
        filterChainDefinitionMap.put("/**/*.woff2", "anon");
        filterChainDefinitionMap.put("/**/*.glb", "anon");
        filterChainDefinitionMap.put("/**/*.wasm", "anon");

        // ---- 开发 / 文档工具 ----
        filterChainDefinitionMap.put("/druid/**", "anon");
        filterChainDefinitionMap.put("/swagger-ui.html", "anon");
        filterChainDefinitionMap.put("/swagger**/**", "anon");
        filterChainDefinitionMap.put("/webjars/**", "anon");
        filterChainDefinitionMap.put("/v3/**", "anon");

        // ---- 公告 / 大屏 ----
        filterChainDefinitionMap.put("/sys/annountCement/show/**", "anon");
        filterChainDefinitionMap.put("/test/bigScreen/**", "anon");
        filterChainDefinitionMap.put("/bigscreen/template1/**", "anon");
        filterChainDefinitionMap.put("/bigscreen/template2/**", "anon");

        // ---- WebSocket / 长连接 ----
        filterChainDefinitionMap.put("/websocket/**", "anon");          // 系统通知和公告
        filterChainDefinitionMap.put("/newsWebsocket/**", "anon");      // CMS 模块
        filterChainDefinitionMap.put("/vxeSocket/**", "anon");          // JVxeTable 无痕刷新
        filterChainDefinitionMap.put("/ws/**", "anon");                 // IoT 设备 WebSocket
        // 部分容器/代理路径下 Shiro 可能按「含 context-path 的 servletPath」匹配，与 /ws/** 并存避免误拦截
        filterChainDefinitionMap.put("/realman-iot/ws/**", "anon");     // IoT WS（context-path 兜底）
        filterChainDefinitionMap.put("/dragChannelSocket/**", "anon");  // 仪表盘按钮通信

        // ---- 其他公开接口 ----
        filterChainDefinitionMap.put("/openapi/call/**", "anon"); // 开放平台接口
        filterChainDefinitionMap.put("/test/seata/**", "anon");   // Seata 测试
        filterChainDefinitionMap.put("/error", "anon");           // 错误路径
        filterChainDefinitionMap.put("/WW_verify*", "anon");      // 企业微信证书

        // ---- JWT 自定义 Filter ----
        Map<String, Filter> filterMap = new HashMap<>(1);
        // cloudServer 为空说明是单体模式，需要加载跨域配置【微服务跨域切换】
        Object cloudServer = env.getProperty(CommonConstant.CLOUD_SERVER_KEY);
        filterMap.put("jwt", new JwtFilter(cloudServer == null));
        shiroFilterFactoryBean.setFilters(filterMap);

        // /** 兜底，必须放最后
        filterChainDefinitionMap.put("/**", "jwt");

        // 未授权统一返回 JSON
        shiroFilterFactoryBean.setUnauthorizedUrl("/sys/common/403");
        shiroFilterFactoryBean.setLoginUrl("/sys/common/403");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterChainDefinitionMap);
        return shiroFilterFactoryBean;
    }

    /**
     * 用 DelegatingFilterProxyRegistrationBean 统一注册 Shiro filter。
     * <p>
     * 相比 FilterRegistrationBean + DelegatingFilterProxy 的组合，此方式会将
     * targetBeanName 写入 Spring Boot 的 seen 集合，从而可靠地防止
     * shiroFilterFactoryBean 被二次自动注册，确保 /* 为唯一注册点。
     * <p>
     * 设置 order = HIGHEST_PRECEDENCE + 1，使 Shiro 在其他自定义 Filter（如跨域）
     * 之前执行；如需调整与跨域 Filter 的相对顺序，修改此值即可。
     */
    @Bean
    public DelegatingFilterProxyRegistrationBean shiroFilterRegistration() {
        DelegatingFilterProxyRegistrationBean registration =
                new DelegatingFilterProxyRegistrationBean(SHIRO_FILTER_BEAN_NAME);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setAsyncSupported(true);
        // 包含 ERROR：Spring Boot 异常 forward 到 /error 时 dispatcher 类型为 ERROR，
        // 确保 Shiro 能处理该路径（/error 已在 anon 白名单，不影响正常流程）
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR
        );
        return registration;
    }

    @Bean("securityManager")
    public DefaultWebSecurityManager securityManager(ShiroRealm myRealm, RedisCacheManager redisCacheManager) {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(myRealm);

        /*
         * 关闭 Shiro 自带 session，详情见文档：
         * http://shiro.apache.org/session-management.html#SessionManagement-StatelessApplications
         */
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        DefaultSessionStorageEvaluator defaultSessionStorageEvaluator = new DefaultSessionStorageEvaluator();
        defaultSessionStorageEvaluator.setSessionStorageEnabled(false);
        subjectDAO.setSessionStorageEvaluator(defaultSessionStorageEvaluator);
        securityManager.setSubjectDAO(subjectDAO);

        // 注入 Spring 管理的单例 RedisCacheManager
        securityManager.setCacheManager(redisCacheManager);
        return securityManager;
    }

    /**
     * 下面的代码是添加注解支持
     */
    @Bean
    @DependsOn("lifecycleBeanPostProcessor")
    public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        defaultAdvisorAutoProxyCreator.setProxyTargetClass(true);
        // 解决重复代理问题 github#994：添加前缀判断，不匹配任何 Advisor
        defaultAdvisorAutoProxyCreator.setUsePrefix(true);
        defaultAdvisorAutoProxyCreator.setAdvisorBeanNamePrefix("_no_advisor");
        return defaultAdvisorAutoProxyCreator;
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(DefaultWebSecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }

    /**
     * cacheManager 缓存 redis 实现（shiro-redis 开源插件）。
     * 声明为 @Bean，由 Spring 管理单例生命周期，避免多次调用产生多个实例和重复连接。
     */
    @Bean
    public RedisCacheManager redisCacheManager(IRedisManager redisManager) {
        log.info("===============(1)创建缓存管理器RedisCacheManager");
        RedisCacheManager redisCacheManager = new RedisCacheManager();
        redisCacheManager.setRedisManager(redisManager);
        // 此处的 id 需对应 User 实体中的 id 字段，用于唯一标识缓存 key
        redisCacheManager.setPrincipalIdFieldName("id");
        // 用户权限信息缓存时间（单位：毫秒）
        redisCacheManager.setExpire(200000);
        return redisCacheManager;
    }

    /**
     * 配置 shiro redisManager（shiro-redis 开源插件）。
     * RedisConfig 位于：
     * jeecg-boot-starter-github/jeecg-boot-common/.../redis/config/RedisConfig.java
     */
    @Bean
    public IRedisManager redisManager() {
        log.info("===============(2)创建RedisManager,连接Redis..");

        // Sentinel 集群模式（issues/5569）
        if (Objects.nonNull(redisProperties)
                && Objects.nonNull(redisProperties.getSentinel())
                && !CollectionUtils.isEmpty(redisProperties.getSentinel().getNodes())) {
            RedisSentinelManager sentinelManager = new RedisSentinelManager();
            sentinelManager.setMasterName(redisProperties.getSentinel().getMaster());
            sentinelManager.setHost(String.join(",", redisProperties.getSentinel().getNodes()));
            sentinelManager.setPassword(redisProperties.getPassword());
            sentinelManager.setDatabase(redisProperties.getDatabase());
            return sentinelManager;
        }

        // 单机模式：集群配置为空或无节点时使用
        if (lettuceConnectionFactory.getClusterConfiguration() == null
                || lettuceConnectionFactory.getClusterConfiguration().getClusterNodes().isEmpty()) {
            RedisManager redisManager = new RedisManager();
            redisManager.setHost(lettuceConnectionFactory.getHostName() + ":" + lettuceConnectionFactory.getPort());
            redisManager.setDatabase(lettuceConnectionFactory.getDatabase());
            redisManager.setTimeout(0);
            if (!StringUtils.isEmpty(lettuceConnectionFactory.getPassword())) {
                redisManager.setPassword(lettuceConnectionFactory.getPassword());
            }
            return redisManager;
        }

        // 集群模式：优先使用集群配置（update-begin Author:scott Date:20210531 issues/I3QNIC）
        RedisClusterManager redisClusterManager = new RedisClusterManager();
        Set<HostAndPort> portSet = new HashSet<>();
        lettuceConnectionFactory.getClusterConfiguration().getClusterNodes()
                .forEach(node -> portSet.add(new HostAndPort(node.getHost(), node.getPort())));
        if (oConvertUtils.isNotEmpty(lettuceConnectionFactory.getPassword())) {
            JedisCluster jedisCluster = new JedisCluster(portSet, 2000, 2000, 5,
                    lettuceConnectionFactory.getPassword(), new GenericObjectPoolConfig());
            redisClusterManager.setPassword(lettuceConnectionFactory.getPassword());
            redisClusterManager.setJedisCluster(jedisCluster);
        } else {
            redisClusterManager.setJedisCluster(new JedisCluster(portSet));
        }
        return redisClusterManager;
    }

    /**
     * 解决 ShiroRequestMappingConfig 获取 requestMappingHandlerMapping Bean 冲突。
     * spring-boot-autoconfigure:3.4.5 与 spring-boot-actuator-autoconfigure:3.4.5 均会注册
     * RequestMappingHandlerMapping，@Primary 确保 Shiro 的 ShiroUrlPathHelper 版本优先。
     */
    @Primary
    @Bean
    public RequestMappingHandlerMapping overridedRequestMappingHandlerMapping() {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setUrlPathHelper(new ShiroUrlPathHelper());
        return mapping;
    }

}