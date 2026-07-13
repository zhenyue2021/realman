package org.jeecg.modules.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.ota.entity.OtaUplinkPollCursor;


@Mapper
public interface OtaUplinkPollCursorMapper extends BaseMapper<OtaUplinkPollCursor> {

    /**
     * 原子的"插入或只前进"落库：多实例并发轮询同一 eventKind 时，任一实例的写入
     * 都不会把稳定 ID 游标覆盖回退。
     */
    @Update("INSERT INTO ota_uplink_poll_cursor (event_kind, cursor_id, updated_at) "
            + "VALUES (#{eventKind}, #{cursorId}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "updated_at = IF(cursor_id IS NULL OR VALUES(cursor_id) > cursor_id, NOW(), updated_at), "
            + "cursor_id = IF(cursor_id IS NULL OR VALUES(cursor_id) > cursor_id, VALUES(cursor_id), cursor_id)")
    void upsertIfAfter(@Param("eventKind") String eventKind, @Param("cursorId") String cursorId);
}
