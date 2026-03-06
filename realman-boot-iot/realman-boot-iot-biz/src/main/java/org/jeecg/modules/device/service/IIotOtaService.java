package org.jeecg.modules.device.service;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotOtaFirmware;
import org.jeecg.modules.device.entity.IotOtaUpgradeTask;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface IIotOtaService extends IService<IotOtaFirmware> {
    String uploadFirmwareChunk(MultipartFile file, String uploadId,
                               Integer chunkIndex, Integer totalChunks);
    List<Integer> getUploadedChunks(String uploadId);
    IotOtaFirmware mergeAndPublish(String uploadId, String firmwareName,
                                    String version, String productId, String description);
    IotOtaUpgradeTask createUpgradeTask(String firmwareId, List<String> deviceIds,
                                         String taskName, String operator);
    void executeUpgradeTask(String taskId);
    void cancelUpgradeTask(String taskId, String operator);
}
