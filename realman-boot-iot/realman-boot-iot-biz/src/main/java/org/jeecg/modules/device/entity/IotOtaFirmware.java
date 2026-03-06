package org.jeecg.modules.device.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @TableName("iot_ota_firmware")
public class IotOtaFirmware implements Serializable {
    @TableId(value="id",type=IdType.ASSIGN_ID) private String id;
    @TableField("firmware_name") private String firmwareName;
    @TableField("version")       private String version;
    @TableField("product_id")    private String productId;
    @TableField("file_path")     private String filePath;
    @TableField("file_name")     private String fileName;
    @TableField("file_size")     private Long fileSize;
    @TableField("file_md5")      private String fileMd5;
    @TableField("download_url")  private String downloadUrl;
    @TableField("description")   private String description;
    @TableField("status")        private Integer status;
    @TableField("force_upgrade") private Integer forceUpgrade;
    @TableField("create_by")     private String createBy;
    @TableField(value="create_time",fill=FieldFill.INSERT) private LocalDateTime createTime;
    @TableField(value="update_time",fill=FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
    @TableLogic @TableField("del_flag") private Integer delFlag;
}
