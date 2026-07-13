package org.jeecg.modules.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.ota.entity.OtaUplinkPollCursor;

import java.time.LocalDateTime;

@Mapper
public interface OtaUplinkPollCursorMapper extends BaseMapper<OtaUplinkPollCursor> {

    /**
     * 原子的"插入或只前进"落库：多实例并发轮询同一 eventKind 时，任一实例的写入
     * 都不会把游标覆盖回退到比当前值更早的时间点。
     */
    @Update("INSERT INTO ota_uplink_poll_cursor (event_kind, cursor_at, updated_at) "
            + "VALUES (#{eventKind}, #{cursorAt}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "cursor_at = IF(VALUES(cursor_at) > cursor_at, VALUES(cursor_at), cursor_at), "
            + "updated_at = IF(VALUES(cursor_at) > cursor_at, NOW(), updated_at)")
    void upsertIfAfter(@Param("eventKind") String eventKind, @Param("cursorAt") LocalDateTime cursorAt);

    @Update("INSERT INTO ota_uplink_poll_cursor (event_kind, cursor_at, cursor_id, updated_at) "
            + "VALUES (#{eventKind}, #{cursorAt}, #{cursorId}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "cursor_at = IF(cursor_id IS NULL OR VALUES(cursor_id) > cursor_id, VALUES(cursor_at), cursor_at), "
            + "updated_at = IF(cursor_id IS NULL OR VALUES(cursor_id) > cursor_id, NOW(), updated_at), "
            + "cursor_id = IF(cursor_id IS NULL OR VALUES(cursor_id) > cursor_id, VALUES(cursor_id), cursor_id)")
    void upsertIfIdAfter(@Param("eventKind") String eventKind,
                         @Param("cursorAt") LocalDateTime cursorAt,
                         @Param("cursorId") String cursorId);
}
