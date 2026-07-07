package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT 日志表归档 / 历史清理（指令记录、操作日志、MQ 消息日志）。
 */
public interface IotLogArchiveMapper {

    List<String> selectCommandRecordIdsBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                              @Param("limit") int limit);

    int insertCommandRecordHistory(@Param("ids") List<String> ids);

    int deleteCommandRecordsByIds(@Param("ids") List<String> ids);

    int deleteCommandRecordHistoryBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                         @Param("limit") int limit);

    List<String> selectOperationLogIdsBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                             @Param("limit") int limit);

    int insertOperationLogHistory(@Param("ids") List<String> ids);

    int deleteOperationLogsByIds(@Param("ids") List<String> ids);

    int deleteOperationLogHistoryBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                        @Param("limit") int limit);

    List<String> selectMqMessageLogIdsBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                             @Param("limit") int limit);

    int insertMqMessageLogHistory(@Param("ids") List<String> ids);

    int deleteMqMessageLogsByIds(@Param("ids") List<String> ids);

    int deleteMqMessageLogHistoryBefore(@Param("beforeTime") LocalDateTime beforeTime,
                                        @Param("limit") int limit);
}
