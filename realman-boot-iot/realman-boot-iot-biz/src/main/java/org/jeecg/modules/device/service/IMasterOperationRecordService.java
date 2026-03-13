package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.MasterOperationRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主控遥操操作记录：记录遥操员使用主控操控哪台机器人完成工单的时间
 */
public interface IMasterOperationRecordService extends IService<MasterOperationRecord> {

    /**
     * 工单开启时创建操作记录（按工单绑定的主控+机器人，每条机器人一条记录）
     *
     * @param workOrderId  工单ID
     * @param operatorId   遥操员ID
     * @param operatorName 遥操员姓名
     * @param startTime    开始操作时间（= 工单开启时间）
     */
    void createRecordsForWorkOrderStart(String workOrderId, String operatorId, String operatorName, LocalDateTime startTime);

    /**
     * 工单结束（提交/超时/关闭）时，将该工单下所有操作记录的结束时间统一更新
     *
     * @param workOrderId 工单ID
     * @param endTime     结束操作时间（正常=提交时间，异常=工单失效时间 plan_end_time）
     */
    void finishByWorkOrder(String workOrderId, LocalDateTime endTime);

    /**
     * 分页查询操作记录（按主控、机器人、时间范围等）
     */
    IPage<MasterOperationRecord> pageRecords(Page<MasterOperationRecord> page,
                                             String controllerId, String controllerCode,
                                             String robotId, LocalDateTime startTimeFrom, LocalDateTime startTimeTo);

    /**
     * 导出用列表（条件同分页）
     */
    List<MasterOperationRecord> listForExport(String controllerId, String controllerCode,
                                              String robotId, LocalDateTime startTimeFrom, LocalDateTime startTimeTo);
}

