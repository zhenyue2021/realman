package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/** 本地盘固件包扫描结果条目，对应 PRD 4.2.5/9.1.2（operate=0）。 */
@Data
@Schema(description = "本地盘固件包扫描结果")
public class LocalScanResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileName;

    private String mountPath;

    private String deviceType;

    private String version;

    @Schema(description = "同目录下是否存在对应 .sig 文件；false 时禁止创建升级任务")
    private boolean sigAvailable;
}
