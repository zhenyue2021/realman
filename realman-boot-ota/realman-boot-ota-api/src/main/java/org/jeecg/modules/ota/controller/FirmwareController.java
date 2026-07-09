package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.service.IOtaFirmwareService;
import org.jeecg.modules.ota.util.RequestUtil;
import org.jeecg.modules.ota.vo.FirmwareDTO;
import org.jeecg.modules.ota.vo.FirmwareListQuery;
import org.jeecg.modules.ota.vo.FirmwareUploadMetadata;
import org.jeecg.modules.ota.vo.LocalScanResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 固件包管理，对齐 OTA 平台详细设计 3.2/3.3（PRD 4.2.1/4.2.5、9.1）。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "固件包管理", description = "上传/本地盘扫描/查询/删除")
public class FirmwareController {

    private final IOtaFirmwareService firmwareService;

    @PostMapping(value = "/api/v1/firmware/packages", consumes = "multipart/form-data")
    @RequiresPermissions("ota:firmware:upload")
    @Operation(summary = "上传固件包（PRD 9.1.1）")
    public Result<FirmwareDTO> upload(@RequestParam("firmwareFile") MultipartFile firmwareFile,
                                       @RequestParam("sigFile") MultipartFile sigFile,
                                       FirmwareUploadMetadata metadata,
                                       HttpServletRequest httpRequest) {
        return Result.ok(firmwareService.upload(firmwareFile, sigFile, metadata, RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/firmware/local-scan")
    @Operation(summary = "本地盘/OSS 固件包扫描（PRD 9.1.2，operate=0 本地盘/operate=1 OSS）")
    public Result<List<LocalScanResult>> scan(@RequestParam(defaultValue = "0") int operate) {
        return Result.ok(firmwareService.scan(operate));
    }

    @GetMapping("/api/v1/firmware/packages")
    @Operation(summary = "查询固件包列表（PRD 4.2.3）")
    public Result<PageResult<FirmwareDTO>> list(FirmwareListQuery query) {
        return Result.ok(firmwareService.list(query));
    }

    @DeleteMapping("/api/v1/firmware/packages/{packageId}")
    @RequiresPermissions("ota:firmware:delete")
    @Operation(summary = "删除固件包（PRD 4.2.4，引用中的任务会阻止删除）")
    public Result<Void> delete(@PathVariable String packageId, HttpServletRequest httpRequest) {
        firmwareService.delete(packageId, RequestUtil.safeUsername(httpRequest));
        return Result.ok();
    }
}
