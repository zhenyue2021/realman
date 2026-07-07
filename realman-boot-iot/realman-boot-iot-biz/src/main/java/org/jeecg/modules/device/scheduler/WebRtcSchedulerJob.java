package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.service.webrtc.RoomTurnRouteCacheService;
import org.springframework.stereotype.Component;

/**
 * WebRTC 相关 XXL-Job 定时任务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebRtcSchedulerJob {

    private final RoomTurnRouteCacheService roomTurnRouteCacheService;

    /**
     * TURN 智能调度缓存午夜清理：仅删除 Redis 缓存，不主动调用 turn_router；
     * 后续由业务 {@code queryOrCreate} → {@code getOrFetch} 按需重建。
     *
     * <p>XXL-Job Handler Name：{@code roomTurnRouteCacheEvictJob}
     * <p>建议 Cron：{@code 0 0 0 * * ?}（每天 0 点，Asia/Shanghai）
     */
    @XxlJob("roomTurnRouteCacheEvictJob")
    public void roomTurnRouteCacheEvictJob() {
        int deleted = roomTurnRouteCacheService.evictAllScheduled();
        String msg = "[TurnRouteCache] 清理完成 deleted=" + deleted;
        XxlJobHelper.log(msg);
        log.info(msg);
    }
}
