package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("iot_slam_map")
public class IotSlamMap implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("robot_code")
    private String robotCode;

    @TableField("master_code")
    private String masterCode;

    @TableField("map_name")
    private String mapName;

    @TableField("map_version")
    private String mapVersion;

    /** MinIO object key: slam-maps/{robotCode}/{commandId}/{filename} */
    @TableField("minio_path")
    private String minioPath;

    @TableField("filename")
    private String filename;

    @TableField("mime_type")
    private String mimeType;

    @TableField("file_size")
    private Integer fileSize;

    @TableField("yaml_content")
    private String yamlContent;

    @TableField("resolution")
    private Double resolution;

    @TableField("width")
    private Integer width;

    @TableField("height")
    private Integer height;

    @TableField("command_id")
    private String commandId;

    @TableField("presigned_url")
    private String presignedUrl;

    @TableField("presigned_url_expire_time")
    private LocalDateTime presignedUrlExpireTime;

    /** 逻辑删除：0=有效，1=已被新地图替代 */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
