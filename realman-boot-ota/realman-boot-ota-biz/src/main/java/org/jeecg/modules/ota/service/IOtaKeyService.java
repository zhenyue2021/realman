package org.jeecg.modules.ota.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.vo.KeyDTO;
import org.jeecg.modules.ota.vo.KeyListQuery;
import org.jeecg.modules.ota.vo.KeyRevokeRequest;
import org.jeecg.modules.ota.vo.KeyUploadRequest;

/** Ed25519 公钥生命周期管理，对齐 OTA 平台详细设计四章（PRD 4.2.2、9.3）。 */
public interface IOtaKeyService {

    KeyDTO upload(KeyUploadRequest request, String operator);

    PageResult<KeyDTO> list(KeyListQuery query);

    KeyDTO activate(String keyId, String operator);

    KeyDTO revoke(String keyId, KeyRevokeRequest request, String operator);

    /** 校验 key_id 状态是否允许下发（仅 active 放行），供固件上传/任务创建/下发前双重校验复用。 */
    void assertDispatchable(String keyId);
}
