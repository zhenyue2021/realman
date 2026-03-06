package org.jeecg.modules.device.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @TableName("iot_device_status")
public class IotDeviceStatus implements Serializable {
    @TableId(value="id",type=IdType.ASSIGN_ID) private String id;
    @TableField("device_id")   private String deviceId;
    @TableField("device_code") private String deviceCode;
    @TableField("temperature")    private BigDecimal temperature;
    @TableField("humidity")       private BigDecimal humidity;
    @TableField("battery_level")  private BigDecimal batteryLevel;
    @TableField("signal_strength") private Integer signalStrength;
    @TableField("longitude")      private BigDecimal longitude;
    @TableField("latitude")       private BigDecimal latitude;
    @TableField("run_status")     private Integer runStatus;
    @TableField("raw_data")       private String rawData;
    @TableField("report_time")    private LocalDateTime reportTime;
    @TableField("receive_time")   private LocalDateTime receiveTime;
}
