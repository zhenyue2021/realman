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
 * <p>本地磁盘存储与 OSS（MinIO）存储均已实现，经上传元数据 storageMode 选择；
 * OSS 未启用（{@code ota.firmware.oss.enabled=false}，默认）时选择 OSS 会
 * 返回明确错误而非伪造数据。
 */
public interface IOtaFirmwareService {

    FirmwareDTO upload(MultipartFile firmwareFile, MultipartFile sigFile, FirmwareUploadMetadata metadata, String operator);

    /** operate=0 本地盘扫描；operate=1 OSS 扫描（需 ota.firmware.oss.enabled=true，否则抛出明确异常）。 */
    List<LocalScanResult> scan(int operate);

    PageResult<FirmwareDTO> list(FirmwareListQuery query);

    void delete(String packageId, String operator);

    /** 内部使用：供前置校验/任务创建读取固件完整信息，不做分页/脱敏。 */
    OtaFirmware getRequired(String packageId);

    /**
     * 重新生成 OSS 预签名下载 URL（PRD "剩余&lt;1小时或收到 ERR_URL_EXPIRED 时自动刷新"，
     * 详细设计 9.9 附录）。仅对 storageSource=OSS 的固件包生效；LOCAL 存储无过期概念，
     * 调用时按无操作处理（记录告警日志）。
     */
    void refreshDownloadUrl(String packageId);
}
