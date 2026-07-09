package org.jeecg.modules.ota.service;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.ota.entity.OtaAuditLog;
import org.jeecg.modules.ota.mapper.OtaAuditLogMapper;
import org.springframework.stereotype.Component;

/**
 * OTA 操作审计写入，对齐 OTA 平台详细设计十三章（PRD 4.8）。同步写库，写操作
 * 频率不高（管理端操作），无需异步化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaAuditService {

    private final OtaAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(String operationType, String operator, String operatorTenantId, String targetTenantId,
                       String auditLevel, String taskId, String packageId, String keyId, Object detail) {
        OtaAuditLog entry = new OtaAuditLog();
        entry.setId(IdUtil.fastSimpleUUID());
        entry.setOperationType(operationType);
        entry.setOperator(operator);
        entry.setOperatorTenantId(operatorTenantId);
        entry.setTargetTenantId(targetTenantId);
        entry.setAuditLevel(auditLevel);
        entry.setTaskId(taskId);
        entry.setPackageId(packageId);
        entry.setKeyId(keyId);
        try {
            entry.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[ota] 审计详情序列化失败 operationType={}: {}", operationType, e.getMessage());
        }
        auditLogMapper.insert(entry);
    }

    public void write(String operationType, String operator, String auditLevel, Object detail) {
        write(operationType, operator, null, null, auditLevel, null, null, null, detail);
    }
}
