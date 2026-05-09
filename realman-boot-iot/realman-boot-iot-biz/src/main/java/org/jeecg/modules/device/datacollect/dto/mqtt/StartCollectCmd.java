package org.jeecg.modules.device.datacollect.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/** 遥操平台 → 机器人：开始采集指令（设计文档 5.1.4.1） */
@Data
@Builder
public class StartCollectCmd {

    private Long timestamp;
    private String deviceSn;
    private String taskId;
    private CollectParams params;

    @Data
    @Builder
    public static class CollectParams {
        @JsonProperty("primary_scene_en")
        private String primarySceneEn;
        @JsonProperty("secondary_scene_en")
        private String secondarySceneEn;
        @JsonProperty("collection_item_name_en")
        private String collectionItemNameEn;
        @JsonProperty("operator_name")
        private String operatorName;
        private String tenantId;
    }
}
