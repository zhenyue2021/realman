package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ota.service.IOtaVersionMatrixService;
import org.jeecg.modules.ota.vo.VersionMatrixQuery;
import org.jeecg.modules.ota.vo.VersionMatrixResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 版本矩阵，对齐 OTA 平台详细设计十二章（PRD 4.3、9.4.4）。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "版本矩阵", description = "群内/仓库双基准版本落后判定")
public class VersionMatrixController {

    private final IOtaVersionMatrixService versionMatrixService;

    @GetMapping("/api/v1/ota/version-matrix")
    @Operation(summary = "查询版本矩阵（PRD 4.3、9.4.4）")
    public Result<VersionMatrixResult> getMatrix(VersionMatrixQuery query) {
        return Result.ok(versionMatrixService.getMatrix(query));
    }
}
