package org.jeecg.modules.ota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 上行事件轮询游标持久化，按 {@code eventKind} 各自维护一条，供
 * {@code OtaUplinkPollingService} 重启后恢复进度，见 OTA 平台详细设计已知限制
 * "轮询游标保存在内存中"的修复。
 */
@Data
@TableName("ota_uplink_poll_cursor")
public class OtaUplinkPollCursor implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("event_kind")
    private String eventKind;

    @TableField("cursor_at")
    private LocalDateTime cursorAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
