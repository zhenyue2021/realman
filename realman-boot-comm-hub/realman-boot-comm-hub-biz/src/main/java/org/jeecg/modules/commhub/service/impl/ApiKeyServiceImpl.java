package org.jeecg.modules.commhub.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.entity.CommHubApiKey;
import org.jeecg.modules.commhub.mapper.CommHubApiKeyMapper;
import org.jeecg.modules.commhub.service.IApiKeyService;
import org.jeecg.modules.commhub.vo.ApiKeyCreateRequest;
import org.jeecg.modules.commhub.vo.ApiKeyCreateResult;
import org.jeecg.modules.commhub.vo.ApiKeyDTO;
import org.jeecg.modules.commhub.vo.ApiKeyListQuery;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link IApiKeyService} 实现。校验顺序：Key 是否存在且 ACTIVE → 设备是否存在且
 * 属于该 Key 的 tenantId（防止 deviceScope 里填了别的租户设备时误放行）→ deviceScope
 * 是否包含该设备 → topicSuffixScope 是否包含该 Topic 后缀（支持 {@code xxx/*} 前缀通配）。
 * 任一环节失败均抛出同一个对外错误码 {@code ERR_API_KEY_UNAUTHORIZED}，不区分具体原因，
 * 避免向未授权调用方泄露"是设备范围问题还是 Topic 范围问题"这类可用于试探的信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl implements IApiKeyService {

    private static final String ERR_API_KEY_INVALID = "ERR_API_KEY_INVALID";
    private static final String ERR_API_KEY_UNAUTHORIZED = "ERR_API_KEY_UNAUTHORIZED";
    private static final String ERR_API_KEY_NOT_FOUND = "ERR_API_KEY_NOT_FOUND";
    private static final String WILDCARD = "*";
    private static final String KEY_PREFIX = "chk_";

    private final CommHubApiKeyMapper apiKeyMapper;
    private final DeviceInfoFeignClient deviceInfoFeignClient;

    @Override
    public ApiKeyCreateResult create(ApiKeyCreateRequest request, String operator) {
        String rawKey = KEY_PREFIX + IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID();

        CommHubApiKey entity = new CommHubApiKey();
        entity.setId(IdUtil.fastSimpleUUID());
        entity.setTenantId(request.getTenantId());
        entity.setApiKeyHash(DigestUtil.sha256Hex(rawKey));
        entity.setKeyPrefix(rawKey.substring(0, Math.min(12, rawKey.length())));
        entity.setDeviceScope(CollectionUtils.isEmpty(request.getDeviceScope())
                ? WILDCARD : String.join(",", request.getDeviceScope()));
        entity.setTopicSuffixScope(CollectionUtils.isEmpty(request.getTopicSuffixScope())
                ? WILDCARD : String.join(",", request.getTopicSuffixScope()));
        entity.setStatus("ACTIVE");
        entity.setCreatedBy(operator);
        apiKeyMapper.insert(entity);

        ApiKeyCreateResult result = new ApiKeyCreateResult();
        result.setId(entity.getId());
        result.setApiKey(rawKey);
        return result;
    }

    @Override
    public PageResult<ApiKeyDTO> list(ApiKeyListQuery query) {
        Page<CommHubApiKey> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<CommHubApiKey> pageResult = apiKeyMapper.selectPage(page, Wrappers.<CommHubApiKey>lambdaQuery()
                .eq(StringUtils.hasText(query.getTenantId()), CommHubApiKey::getTenantId, query.getTenantId())
                .eq(StringUtils.hasText(query.getStatus()), CommHubApiKey::getStatus, query.getStatus())
                .orderByDesc(CommHubApiKey::getCreatedAt));

        List<ApiKeyDTO> records = pageResult.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    public void revoke(String id) {
        CommHubApiKey entity = apiKeyMapper.selectById(id);
        if (entity == null) {
            throw new JeecgBootBizTipException(ERR_API_KEY_NOT_FOUND);
        }
        entity.setStatus("REVOKED");
        apiKeyMapper.updateById(entity);
    }

    @Override
    public String assertAuthorized(String rawApiKey, String deviceId, String topicSuffix) {
        if (!StringUtils.hasText(rawApiKey)) {
            throw new JeecgBootBizTipException(ERR_API_KEY_INVALID);
        }
        CommHubApiKey entity = apiKeyMapper.selectOne(Wrappers.<CommHubApiKey>lambdaQuery()
                .eq(CommHubApiKey::getApiKeyHash, DigestUtil.sha256Hex(rawApiKey))
                .last("LIMIT 1"));
        if (entity == null || !"ACTIVE".equals(entity.getStatus())) {
            throw new JeecgBootBizTipException(ERR_API_KEY_INVALID);
        }

        DeviceInfoDTO device = getDeviceSafely(deviceId);
        if (device == null || !entity.getTenantId().equals(device.getTenantId())) {
            log.warn("[comm-hub] API Key 越权尝试（设备不存在或不属于该 Key 的租户）apiKeyId={} deviceId={}", entity.getId(), deviceId);
            throw new JeecgBootBizTipException(ERR_API_KEY_UNAUTHORIZED);
        }
        // deviceScope 可能用 deviceId 或 deviceCode 配置，调用方传入的路径参数也可能是任一种表示，两个都比对一遍
        if (!matchesScope(entity.getDeviceScope(), device.getDeviceId())
                && !matchesScope(entity.getDeviceScope(), device.getDeviceCode())) {
            log.warn("[comm-hub] API Key 越权尝试（设备超出 deviceScope）apiKeyId={} deviceId={}", entity.getId(), deviceId);
            throw new JeecgBootBizTipException(ERR_API_KEY_UNAUTHORIZED);
        }
        if (!matchesTopicScope(entity.getTopicSuffixScope(), topicSuffix)) {
            log.warn("[comm-hub] API Key 越权尝试（Topic 超出 topicSuffixScope）apiKeyId={} topicSuffix={}", entity.getId(), topicSuffix);
            throw new JeecgBootBizTipException(ERR_API_KEY_UNAUTHORIZED);
        }
        return entity.getId();
    }

    private boolean matchesScope(String scope, String value) {
        if (!StringUtils.hasText(scope) || WILDCARD.equals(scope.trim())) {
            return true;
        }
        for (String entry : scope.split(",")) {
            if (entry.trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** 支持 {@code xxx/*} 前缀通配，例如 scope 里的 "command/*" 匹配 "command/restart"。 */
    private boolean matchesTopicScope(String scope, String topicSuffix) {
        if (!StringUtils.hasText(scope) || WILDCARD.equals(scope.trim())) {
            return true;
        }
        for (String entry : scope.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.equals(topicSuffix)) {
                return true;
            }
            if (trimmed.endsWith("*") && topicSuffix != null
                    && topicSuffix.startsWith(trimmed.substring(0, trimmed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** deviceId 路径参数既可能是内部 UUID 也可能是设备码，与 {@code MqttPublisher#resolveDeviceCode} 同样兼容两种输入。 */
    private DeviceInfoDTO getDeviceSafely(String deviceIdOrCode) {
        try {
            Result<DeviceInfoDTO> byCode = deviceInfoFeignClient.getDeviceByCode(deviceIdOrCode);
            if (byCode != null && byCode.isSuccess() && byCode.getResult() != null) {
                return byCode.getResult();
            }
        } catch (Exception ignored) {
            // 按 deviceCode 查询未命中，继续尝试按内部 deviceId 查询
        }
        try {
            Result<DeviceInfoDTO> byId = deviceInfoFeignClient.getDevice(deviceIdOrCode);
            return byId != null && byId.isSuccess() ? byId.getResult() : null;
        } catch (Exception e) {
            log.debug("[comm-hub] 设备信息基础服务查询未命中或不可用 deviceIdOrCode={}: {}", deviceIdOrCode, e.getMessage());
            return null;
        }
    }

    private ApiKeyDTO toDTO(CommHubApiKey entity) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setKeyPrefix(entity.getKeyPrefix());
        dto.setDeviceScope(toList(entity.getDeviceScope()));
        dto.setTopicSuffixScope(toList(entity.getTopicSuffixScope()));
        dto.setStatus(entity.getStatus());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private List<String> toList(String commaSeparated) {
        return StringUtils.hasText(commaSeparated) ? Arrays.asList(commaSeparated.split(",")) : Collections.emptyList();
    }
}
