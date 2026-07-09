package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "API Key 查询")
public class ApiKeyListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String status;

    private Integer pageNo = 1;

    private Integer pageSize = 20;
}
