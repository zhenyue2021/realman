package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "创建 API Key 结果")
public class ApiKeyCreateResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    @Schema(description = "原始 API Key，仅本次返回，不再落库明文，请妥善保存")
    private String apiKey;
}
