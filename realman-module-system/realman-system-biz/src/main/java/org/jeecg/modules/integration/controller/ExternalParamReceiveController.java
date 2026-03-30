package org.jeecg.modules.integration.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.integration.config.IntegrationProperties;
import org.jeecg.modules.integration.dto.ExternalParamReceiveDTO;
import org.jeecg.modules.integration.dto.TempTokenRequestDTO;
import org.jeecg.modules.integration.service.IExternalParamRecordService;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.service.ISysUserService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/integration/external")
@RequiredArgsConstructor
@Tag(name = "外部系统参数接收")
public class ExternalParamReceiveController {

    private final ISysUserService sysUserService;
    private final RedisUtil redisUtil;
    private final IntegrationProperties integrationProperties;
    private final IExternalParamRecordService externalParamRecordService;

    /**
     * 为外部系统颁发临时 Token
     *
     * <p>调用示例：
     * <pre>
     * POST http://172.16.44.66:8080/realman-root/api/integration/external/temp-token
     * Content-Type: application/json
     *
     * {
     *   "sourceSystem": "DEW",
     *   "expireSeconds": 3600
     * }
     * </pre>
     *
     * <p>返回的 token 放入后续请求 Header：X-Access-Token: {token}
     */
    @IgnoreAuth
    @PostMapping("/temp-token")
    @Operation(summary = "为外部系统颁发临时 Token")
    public Result<Map<String, Object>> generateTempToken(@Valid @RequestBody TempTokenRequestDTO dto) {
        IntegrationProperties.TempToken cfg = integrationProperties.getTempToken();

        // 1. 校验 sourceSystem 是否在白名单内（大小写不敏感）
        boolean allowed = cfg.getAllowedSourceSystems().stream()
                .anyMatch(s -> s.equalsIgnoreCase(dto.getSourceSystem()));
        if (!allowed) {
            log.warn("[TempToken] 非法来源系统，拒绝颁发: sourceSystem={}", dto.getSourceSystem());
            return Result.error("sourceSystem 不被允许");
        }

        // 2. 加载内部服务账号（配置于 integration.temp-token.service-username）
        String serviceUsername = cfg.getServiceUsername();
        SysUser serviceUser = sysUserService.getOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, serviceUsername));
        if (serviceUser == null) {
            log.error("[TempToken] 内部服务账号不存在，请检查配置: serviceUsername={}", serviceUsername);
            return Result.error("服务暂不可用，请联系管理员");
        }

        // 3. 校验服务账号状态是否正常
        Result<?> effectiveCheck = sysUserService.checkUserIsEffective(serviceUser);
        if (!effectiveCheck.isSuccess()) {
            log.error("[TempToken] 内部服务账号状态异常: serviceUsername={}, msg={}",
                    serviceUsername, effectiveCheck.getMessage());
            return Result.error("服务暂不可用，请联系管理员");
        }

        // 4. 生成 JWT，有效期与 expireSeconds 一致
        long expireMillis = (long) dto.getExpireSeconds() * 1000;
        String token = JwtUtil.sign(serviceUser.getUsername(), serviceUser.getPassword(), expireMillis);

        // 5. 写入 Redis，TTL = expireSeconds（Redis 过期即 Token 不可用）
        redisUtil.set(CommonConstant.PREFIX_USER_TOKEN + token, token);
        redisUtil.expire(CommonConstant.PREFIX_USER_TOKEN + token, dto.getExpireSeconds());

        // 6. 组装响应
        String expireAt = LocalDateTime.now()
                .plusSeconds(dto.getExpireSeconds())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token",         token);
        data.put("expireSeconds", dto.getExpireSeconds());
        data.put("expireAt",      expireAt);

        log.info("[TempToken] 临时 Token 已颁发: sourceSystem={}, serviceUser={}, expireSeconds={}, expireAt={}",
                dto.getSourceSystem(), serviceUsername, dto.getExpireSeconds(), expireAt);
        return Result.ok(data);
    }

    @PostMapping("/receive")
    @Operation(summary = "外部系统传参接收（POST JSON）")
    public Result<Map<String, Object>> receive(@Valid @RequestBody ExternalParamReceiveDTO dto) {
        log.info("[ExternalParam] 接收参数: sourceSystem={}, requestId={}, bizType={}",
                dto.getSourceSystem(), dto.getRequestId(), dto.getBizType());

        boolean stored = externalParamRecordService.receiveAndStore(dto);

        Map<String, Object> ack = new HashMap<>();
        ack.put("received", true);
        ack.put("stored", stored);
        return Result.ok(ack);
    }

    @GetMapping("/params/{sourceSystem}")
    @Operation(summary = "查询指定来源系统的最新缓存参数")
    public Result<Map<String, Object>> getCachedParams(@PathVariable String sourceSystem) {
        Map<String, Object> data = externalParamRecordService.getCachedData(sourceSystem);
        if (data == null) {
            return Result.error("暂无缓存数据，sourceSystem=" + sourceSystem);
        }
        return Result.ok(data);
    }
}

