package org.jeecg.modules.ota.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.entity.OtaKey;
import org.jeecg.modules.ota.mapper.OtaKeyMapper;
import org.jeecg.modules.ota.service.IOtaKeyService;
import org.jeecg.modules.ota.service.OtaAuditService;
import org.jeecg.modules.ota.vo.KeyDTO;
import org.jeecg.modules.ota.vo.KeyListQuery;
import org.jeecg.modules.ota.vo.KeyRevokeRequest;
import org.jeecg.modules.ota.vo.KeyUploadRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ed25519 公钥生命周期实现，对齐 OTA 平台详细设计四章（PRD 4.2.2、9.3）。
 *
 * <p>PEM 格式校验使用 JDK 原生 Ed25519 支持（JEP 339，Java 15+），不引入第三方
 * 加密库；只接受标准 X.509 SubjectPublicKeyInfo DER 编码（{@code openssl genpkey
 * -algorithm ed25519}/{@code openssl pkey -pubout} 的标准产物），裸 32 字节
 * raw key（不含 DER 外壳）不在本轮支持范围，属已知限制。
 */
@Service
@RequiredArgsConstructor
public class OtaKeyServiceImpl implements IOtaKeyService {

    private static final String ERR_KEY_REVOKED = "ERR_KEY_REVOKED";
    private static final String ERR_KEY_NOT_FOUND = "ERR_KEY_NOT_FOUND";
    private static final String CONFIRM_REVOKE = "REVOKE";
    private static final int FINGERPRINT_LENGTH = 32;

    private final OtaKeyMapper keyMapper;
    private final OtaAuditService auditService;

    @Override
    public KeyDTO upload(KeyUploadRequest request, String operator) {
        byte[] derBytes = parseAndValidateEd25519Pem(request.getPublicKeyPem());

        OtaKey key = new OtaKey();
        key.setKeyId(IdUtil.fastSimpleUUID());
        key.setAlgorithm("Ed25519");
        key.setPublicKeyPem(request.getPublicKeyPem().trim());
        key.setKeyFingerprint(DigestUtil.sha256Hex(derBytes).substring(0, FINGERPRINT_LENGTH));
        key.setKeyAlias(request.getKeyAlias());
        key.setStatus("pending_activation");
        key.setCreatedBy(operator);
        keyMapper.insert(key);

        auditService.write("KEY_UPLOAD", operator, null, null, "normal", null, null, key.getKeyId(),
                java.util.Map.of("keyFingerprint", key.getKeyFingerprint()));
        return toDTO(key);
    }

    @Override
    public PageResult<KeyDTO> list(KeyListQuery query) {
        Page<OtaKey> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<OtaKey> pageResult = keyMapper.selectPage(page, Wrappers.<OtaKey>lambdaQuery()
                .eq(StringUtils.hasText(query.getStatus()), OtaKey::getStatus, query.getStatus())
                .orderByDesc(OtaKey::getCreatedAt));
        List<KeyDTO> records = pageResult.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    public KeyDTO activate(String keyId, String operator) {
        OtaKey target = keyMapper.selectById(keyId);
        if (target == null) {
            throw new JeecgBootBizTipException(ERR_KEY_NOT_FOUND);
        }
        OtaKey previousActive = keyMapper.selectOne(Wrappers.<OtaKey>lambdaQuery().eq(OtaKey::getStatus, "active"));
        LocalDateTime now = LocalDateTime.now();
        if (previousActive != null && !previousActive.getKeyId().equals(keyId)) {
            previousActive.setStatus("revoked");
            previousActive.setRevokedAt(now);
            previousActive.setRevokeReason("轮换替换");
            keyMapper.updateById(previousActive);
        }
        target.setStatus("active");
        target.setActivatedAt(now);
        keyMapper.updateById(target);

        auditService.write("KEY_ACTIVATE", operator, null, null, "normal", null, null, keyId,
                java.util.Map.of("oldKeyId", previousActive == null ? "" : previousActive.getKeyId(), "newKeyId", keyId));
        return toDTO(target);
    }

    @Override
    public KeyDTO revoke(String keyId, KeyRevokeRequest request, String operator) {
        if (!CONFIRM_REVOKE.equals(request.getConfirmText())) {
            throw new JeecgBootBizTipException("ERR_CONFIRM_TEXT_MISMATCH");
        }
        OtaKey key = keyMapper.selectById(keyId);
        if (key == null) {
            throw new JeecgBootBizTipException(ERR_KEY_NOT_FOUND);
        }
        if ("revoked".equals(key.getStatus())) {
            throw new JeecgBootBizTipException("公钥已处于 revoked 状态");
        }
        key.setStatus("revoked");
        key.setRevokedAt(LocalDateTime.now());
        key.setRevokeReason(request.getReason());
        keyMapper.updateById(key);

        auditService.write("KEY_REVOKE", operator, null, null, "high", null, null, keyId,
                java.util.Map.of("reason", request.getReason() == null ? "" : request.getReason()));
        return toDTO(key);
    }

    @Override
    public void assertDispatchable(String keyId) {
        OtaKey key = keyMapper.selectById(keyId);
        if (key == null || !"active".equals(key.getStatus())) {
            String status = key == null ? "NOT_FOUND" : key.getStatus();
            throw new JeecgBootBizTipException(ERR_KEY_REVOKED + ": key_id=" + keyId + " status=" + status);
        }
    }

    private byte[] parseAndValidateEd25519Pem(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] derBytes;
        try {
            derBytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new JeecgBootBizTipException("ERR_KEY_FORMAT_INVALID: PEM 内容非合法 Base64");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(derBytes));
            if (!"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
                throw new JeecgBootBizTipException("ERR_KEY_FORMAT_INVALID: 算法类型须为 Ed25519，不支持 RSA/ECDSA");
            }
        } catch (JeecgBootBizTipException e) {
            throw e;
        } catch (Exception e) {
            throw new JeecgBootBizTipException("ERR_KEY_FORMAT_INVALID: 公钥格式非法或非 Ed25519 算法：" + e.getMessage());
        }
        return derBytes;
    }

    private KeyDTO toDTO(OtaKey key) {
        KeyDTO dto = new KeyDTO();
        dto.setKeyId(key.getKeyId());
        dto.setKeyFingerprint(key.getKeyFingerprint());
        dto.setKeyAlias(key.getKeyAlias());
        dto.setStatus(key.getStatus());
        dto.setCreatedBy(key.getCreatedBy());
        dto.setCreatedAt(key.getCreatedAt());
        dto.setActivatedAt(key.getActivatedAt());
        dto.setRevokedAt(key.getRevokedAt());
        return dto;
    }
}
