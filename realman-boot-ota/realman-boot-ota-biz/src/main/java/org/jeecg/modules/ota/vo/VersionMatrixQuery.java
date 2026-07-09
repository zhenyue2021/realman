package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 GET /api/v1/versions/matrix（PRD 9.4.4）。 */
@Data
@Schema(description = "版本矩阵查询条件")
public class VersionMatrixQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceType;

    @NotBlank
    private String model;
}
