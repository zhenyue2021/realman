package org.jeecg.modules.device.constant;

/**
 * 工单管理模块常量定义
 */
public interface WorkOrderConstant {

    /**
     * 工单流转状态
     * <p>状态流转：PENDING → STARTED → SUBMITTED → COMPLETED
     *                        ↓
     *                        → TIMEOUT → CLOSED
     */
    interface ORDER_STATUS {
        /**
         * 待开始
         */
        String PENDING = "PENDING";
        /**
         * 以开始
         */
        String STARTED = "STARTED";
        /**
         * 已提交
         */
        String SUBMITTED = "SUBMITTED";
        /**
         * 已完成
         */
        String COMPLETED = "COMPLETED";
        /**
         * 超时
         */
        String TIMEOUT = "TIMEOUT";
        /**
         * 关闭
         */
        String CLOSED = "CLOSED";
    }

}
