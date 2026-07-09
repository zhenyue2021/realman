package org.jeecg.modules.ota.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.vo.FirmwareDTO;
import org.jeecg.modules.ota.vo.FirmwareListQuery;
import org.jeecg.modules.ota.vo.FirmwareUploadMetadata;
import org.jeecg.modules.ota.vo.LocalScanResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 固件包管理，对齐 OTA 平台详细设计 3.2/3.3（PRD 4.2.1/4.2.5、9.1）。
 *
 * <p>已知限制：本轮只实现本地磁盘存储与本地盘扫描（operate=0）；OSS/S3 扫描
 * （operate=1）需要真实云厂商 SDK 与凭证，本轮不接入，调用时返回明确的
 * "暂不支持"提示而非伪造数据。
 */
public interface IOtaFirmwareService {

    FirmwareDTO upload(MultipartFile firmwareFile, MultipartFile sigFile, FirmwareUploadMetadata metadata, String operator);

    /** operate=0 本地盘扫描；operate=1 OSS 扫描（本轮未实现，抛出明确异常）。 */
    List<LocalScanResult> scan(int operate);

    PageResult<FirmwareDTO> list(FirmwareListQuery query);

    void delete(String packageId, String operator);

    /** 内部使用：供前置校验/任务创建读取固件完整信息，不做分页/脱敏。 */
    OtaFirmware getRequired(String packageId);
}
