package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "公钥列表查询条件")
public class KeyListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String status;
}
