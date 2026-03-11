package org.jeecg.modules.device.entity.workorder;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单附件/佐证材料
 */
@Data
@TableName("work_order_attachment")
public class WorkOrderAttachment implements Serializable {

    @TableId
    private String id;

    @TableField("work_order_id")
    private String workOrderId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_url")
    private String fileUrl;

    @TableField("description")
    private String description;

    @TableField("create_by")
    private String createBy;

    @TableField("create_time")
    private LocalDateTime createTime;
}

