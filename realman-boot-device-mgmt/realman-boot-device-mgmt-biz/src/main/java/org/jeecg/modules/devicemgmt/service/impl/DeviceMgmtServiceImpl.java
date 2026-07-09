package org.jeecg.modules.devicemgmt.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.devicemgmt.config.DeviceTokenProperties;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;
import org.jeecg.modules.devicemgmt.entity.DeviceCredential;
import org.jeecg.modules.devicemgmt.entity.DeviceRegistrationSecret;
import org.jeecg.modules.devicemgmt.mapper.DeviceCredentialMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceRegistrationSecretMapper;
import org.jeecg.modules.devicemgmt.service.DeviceRateLimitService;
import org.jeecg.modules.devicemgmt.service.DeviceSecretCacheService;
import org.jeecg.modules.devicemgmt.service.IDeviceMgmtService;
import org.jeecg.modules.devicemgmt.vo.CachedDeviceSecret;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceRegisterWriteRequest;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * 设备管理业务平台业务实现。只覆盖 {@code DeviceMgmtFeignClient} 契约的四个方法，
 * 见本模块 pom.xml 说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceMgmtServiceImpl implements IDeviceMgmtService {

    private static final String ERR_DEVICE_NOT_AUTHORIZED = "ERR_DEVICE_NOT_AUTHORIZED";
    private static final String ERR_TOKEN_REVOKED = "ERR_TOKEN_REVOKED";
    private static final String ERR_TOKEN_EXPIRED = "ERR_TOKEN_EXPIRED";
    private static final String ERR_TOKEN_INVALID = "ERR_TOKEN_INVALID";
    private static final String ERR_REGISTER_RATE_LIMIT = "ERR_REGISTER_RATE_LIMIT";
    private static final String RATE_LIMIT_SCOPE_REGISTER = "register";
    private static final int RATE_LIMIT_REGISTER_PER_HOUR = 5;

    private static final String CLAIM_DEVICE_ID = "device_id";
    private static final String CLAIM_DEVICE_TYPE = "device_type";
    private static final String CLAIM_TENANT_ID = "tenant_id";

    private final DeviceCredentialMapper credentialMapper;
    private final DeviceRegistrationSecretMapper registrationSecretMapper;
    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final DeviceTokenProperties tokenProperties;
    private final DeviceSecretCacheService secretCacheService;
    private final DeviceRateLimitService rateLimitService;

    @Override
    public DeviceSecretValidationResult validateSecret(String deviceCode, String deviceSecret) {
        DeviceSecretValidationResult result = new DeviceSecretValidationResult();

        CachedDeviceSecret cached = secretCacheService.get(deviceCode).orElse(null);
        String deviceId;
        String deviceSecretHash;
        if (cached != null) {
            deviceId = cached.getDeviceId();
            deviceSecretHash = cached.getDeviceSecretHash();
        } else {
            DeviceInfoDTO device = getDeviceByCodeSafely(deviceCode);
            if (device == null) {
                result.setAllow(false);
                result.setReason(ERR_DEVICE_NOT_AUTHORIZED);
                return result;
            }
            DeviceCredential credential = credentialMapper.selectById(device.getDeviceId());
            if (credential == null || !StringUtils.hasText(credential.getDeviceSecretHash())) {
                log.warn("[device-mgmt] validateSecret 设备无凭证记录 deviceCode={}", deviceCode);
                result.setAllow(false);
                result.setReason(ERR_DEVICE_NOT_AUTHORIZED);
                return result;
            }
            deviceId = device.getDeviceId();
            deviceSecretHash = credential.getDeviceSecretHash();
            secretCacheService.put(deviceCode, new CachedDeviceSecret(deviceId, deviceSecretHash));
        }

        boolean match = DigestUtil.sha256Hex(deviceSecret).equals(deviceSecretHash);
        result.setAllow(match);
        result.setDeviceId(deviceId);
        if (!match) {
            result.setReason(ERR_DEVICE_NOT_AUTHORIZED);
            // 密钥已变更但缓存未及时失效时，命中的旧缓存会导致误拒绝；清掉后下次回源重建
            if (cached != null) {
                secretCacheService.evict(deviceCode);
            }
        }
        return result;
    }

    @Override
    public List<DeviceAclRuleDTO> getAclRules(String deviceCode) {
        // 沿用现状 DeviceSecretService#validateAcl 的规则集合：标准命名空间 + 历史遗留兼容前缀
        DeviceAclRuleDTO standard = rule("device/" + deviceCode + "/#", "ALL");
        DeviceAclRuleDTO legacyMaster = rule("/" + deviceCode + "/master/#", "ALL");
        DeviceAclRuleDTO legacySlave = rule("/" + deviceCode + "/slave/#", "ALL");
        return Arrays.asList(standard, legacyMaster, legacySlave);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceProvisionResult provision(DeviceProvisionRequest request) {
        if (rateLimitService.isExceeded(RATE_LIMIT_SCOPE_REGISTER, request.getDeviceCode(), RATE_LIMIT_REGISTER_PER_HOUR)) {
            throw new JeecgBootBizTipException(ERR_REGISTER_RATE_LIMIT);
        }
        DeviceRegistrationSecret secret = consumeRegistrationSecret(request);

        DeviceInfoDTO existing = getDeviceByCodeSafely(request.getDeviceCode());
        boolean reRegistration = existing != null;
        String deviceId = reRegistration ? existing.getDeviceId() : IdUtil.fastSimpleUUID();

        if (!reRegistration) {
            DeviceRegisterWriteRequest registerRequest = new DeviceRegisterWriteRequest();
            registerRequest.setDeviceId(deviceId);
            registerRequest.setDeviceCode(request.getDeviceCode());
            registerRequest.setDeviceType(request.getDeviceType());
            registerRequest.setTenantId(request.getTenantId());
            registerRequest.setDeviceModel(request.getDeviceModel());
            registerRequest.setMacAddress(request.getMacAddress());
            Result<Void> registerResult = deviceInfoFeignClient.register(registerRequest);
            if (registerResult == null || !registerResult.isSuccess()) {
                throw new JeecgBootBizTipException("写入设备信息基础服务失败，注册中止：" + request.getDeviceCode());
            }
        }

        String plainSecret = IdUtil.fastSimpleUUID();
        String plainToken = issueToken(deviceId, request.getDeviceType(), request.getTenantId());
        LocalDateTime tokenExpiresAt = LocalDateTime.now().plusDays(tokenProperties.getExpiryDays());

        DeviceCredential credential = credentialMapper.selectById(deviceId);
        if (credential == null) {
            credential = new DeviceCredential();
            credential.setDeviceId(deviceId);
            credential.setDeviceSecretVersion(1);
        } else {
            credential.setDeviceSecretVersion(
                    credential.getDeviceSecretVersion() == null ? 1 : credential.getDeviceSecretVersion() + 1);
        }
        credential.setDeviceSecretHash(DigestUtil.sha256Hex(plainSecret));
        credential.setTokenJti(IdUtil.fastSimpleUUID());
        credential.setTokenIssuedAt(LocalDateTime.now());
        credential.setTokenExpiresAt(tokenExpiresAt);
        credential.setTokenRevokedAt(null);
        credential.setTokenRevokeReason(null);
        if (credential.getDeviceSecretVersion() == 1) {
            credentialMapper.insert(credential);
        } else {
            credentialMapper.updateById(credential);
        }
        secretCacheService.evict(request.getDeviceCode());

        log.info("[device-mgmt] provision 完成 deviceCode={} deviceId={} reRegistration={} secretId={}",
                request.getDeviceCode(), deviceId, reRegistration, secret.getId());

        DeviceProvisionResult result = new DeviceProvisionResult();
        result.setDeviceId(deviceId);
        result.setDeviceSecret(plainSecret);
        result.setDeviceToken(plainToken);
        result.setTokenExpiresAt(tokenExpiresAt);
        result.setReRegistration(reRegistration);
        return result;
    }

    @Override
    public DeviceTokenValidationResult validateToken(String deviceToken) {
        DeviceTokenValidationResult result = new DeviceTokenValidationResult();
        DecodedJWT decoded;
        try {
            Algorithm algorithm = Algorithm.HMAC256(tokenProperties.getSecret());
            decoded = JWT.require(algorithm)
                    .withIssuer(tokenProperties.getIssuer())
                    .build()
                    .verify(deviceToken);
        } catch (JWTVerificationException e) {
            log.warn("[device-mgmt] validateToken 校验失败：{}", e.getMessage());
            result.setValid(false);
            result.setReason(ERR_TOKEN_EXPIRED.equals(reasonFromException(e)) ? ERR_TOKEN_EXPIRED : ERR_TOKEN_INVALID);
            return result;
        }

        String deviceId = decoded.getClaim(CLAIM_DEVICE_ID).asString();
        DeviceCredential credential = credentialMapper.selectById(deviceId);
        if (credential == null || credential.getTokenRevokedAt() != null) {
            result.setValid(false);
            result.setReason(ERR_TOKEN_REVOKED);
            return result;
        }

        result.setValid(true);
        result.setDeviceId(deviceId);
        result.setTenantId(decoded.getClaim(CLAIM_TENANT_ID).asString());
        String deviceTypeStr = decoded.getClaim(CLAIM_DEVICE_TYPE).asString();
        result.setDeviceType(StringUtils.hasText(deviceTypeStr) ? DeviceType.valueOf(deviceTypeStr) : null);
        return result;
    }

    @Override
    public String issueToken(String deviceId, DeviceType deviceType, String tenantId) {
        Algorithm algorithm = Algorithm.HMAC256(tokenProperties.getSecret());
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenProperties.getExpiryDays(), ChronoUnit.DAYS);
        return JWT.create()
                .withIssuer(tokenProperties.getIssuer())
                .withJWTId(IdUtil.fastSimpleUUID())
                .withClaim(CLAIM_DEVICE_ID, deviceId)
                .withClaim(CLAIM_DEVICE_TYPE, deviceType.name())
                .withClaim(CLAIM_TENANT_ID, tenantId)
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .sign(algorithm);
    }

    private DeviceRegistrationSecret consumeRegistrationSecret(DeviceProvisionRequest request) {
        DeviceRegistrationSecret secret = registrationSecretMapper.selectOne(Wrappers.<DeviceRegistrationSecret>lambdaQuery()
                .eq(DeviceRegistrationSecret::getDeviceCode, request.getDeviceCode())
                .eq(DeviceRegistrationSecret::getStatus, "UNUSED")
                .orderByDesc(DeviceRegistrationSecret::getCreatedAt)
                .last("LIMIT 1"));
        if (secret == null
                || !DigestUtil.sha256Hex(request.getDeviceRegistrationSecret()).equals(secret.getSecretHash())
                || !request.getTenantId().equals(secret.getTenantId())
                || secret.getExpiresAt() == null
                || secret.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new JeecgBootBizTipException(ERR_DEVICE_NOT_AUTHORIZED);
        }
        secret.setStatus("USED");
        secret.setUsedAt(LocalDateTime.now());
        registrationSecretMapper.updateById(secret);
        return secret;
    }

    private DeviceInfoDTO getDeviceByCodeSafely(String deviceCode) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDeviceByCode(deviceCode);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            log.debug("[device-mgmt] 设备信息基础服务查询未命中或不可用 deviceCode={}: {}", deviceCode, e.getMessage());
            return null;
        }
    }

    private static DeviceAclRuleDTO rule(String topicPattern, String action) {
        DeviceAclRuleDTO dto = new DeviceAclRuleDTO();
        dto.setTopicPattern(topicPattern);
        dto.setAction(action);
        return dto;
    }

    private static String reasonFromException(JWTVerificationException e) {
        return e.getClass().getSimpleName().contains("TokenExpired") ? ERR_TOKEN_EXPIRED : ERR_TOKEN_INVALID;
    }
}
