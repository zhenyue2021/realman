package org.jeecg.modules.device.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @TableName("iot_device_operation_log")
public class IotDeviceOperationLog implements Serializable {
    @TableId(value="id",type=IdType.ASSIGN_ID) private String id;
    @TableField("device_id")        private String deviceId;
    @TableField("device_code")      private String deviceCode;
    @TableField("operation_type")   private String operationType;
    @TableField("operation_desc")   private String operationDesc;
    @TableField("operation_detail") private String operationDetail;
    @TableField("operation_source") private String operationSource;
    @TableField("operation_result") private String operationResult;
    @TableField("fail_reason")      private String failReason;
    @TableField("operator")         private String operator;
    @TableField("client_ip")        private String clientIp;
    @TableField("create_time")      private LocalDateTime createTime;
    @TableField("operation_time")   private LocalDateTime operationTime;
}
