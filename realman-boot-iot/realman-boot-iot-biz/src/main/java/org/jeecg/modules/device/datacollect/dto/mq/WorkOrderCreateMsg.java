package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Data;

import java.util.List;

/** Darwin → Teleop（RocketMQ）：工单推送消息，data 中每条 item.id 即对应 work_order 主键 */
@Data
public class WorkOrderCreateMsg {

    private String traceId;
    private String tenant;
    /** 执行该工单的机器人设备编码（对应 iot_device.device_code），用于写入 work_order_device */
    private String deviceCode;
    private List<WorkOrderItem> data;

    @Data
    public static class WorkOrderItem {
        /** Darwin 工单 ID，幂等键 */
        private String id;
        /** "true" 表示该工单已被达尔文侧删除 */
        private String deleted;
        private String completed;
        private String quotaType;
        /** 总条数 */
        private String quotaValue;
        private CollectionItem collectionItem;
        private CollectionPlan collectionPlan;
        private CollectionProject collectionProject;
        private Scene level1Scene;
        private Scene level2Scene;
    }

    @Data
    public static class CollectionItem {
        private String id;
        private String name;
        private String nameEn;
        /** 动作列表，格式化后存入 task_desc */
        private List<Action> actions;
    }

    @Data
    public static class Action {
        private String name;
        private String nameEn;
    }

    @Data
    public static class CollectionPlan {
        private String id;
        /** 计划描述，对应 task_name */
        private String name;
        private String nameEn;
        /** 计划开始时间，格式 yyyy-MM-dd HH:mm:ss */
        private String beginAt;
        /** 计划结束时间，格式 yyyy-MM-dd HH:mm:ss */
        private String endAt;
    }

    @Data
    public static class CollectionProject {
        private String id;
        private String name;
        private String nameEn;
        private String customerName;
    }

    @Data
    public static class Scene {
        private String id;
        private String name;
        private String nameEn;
        private String description;
    }
}
