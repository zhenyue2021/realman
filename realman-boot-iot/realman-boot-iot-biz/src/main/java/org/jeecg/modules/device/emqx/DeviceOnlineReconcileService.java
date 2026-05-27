package org.jeecg.modules.device.emqx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.handler.DeviceOnlineOfflineHandler;
import org.jeecg.modules.device.mqtt.handler.DeviceOnlineOfflineHandler.ReconcileOnlineResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台启动时与 EMQX 对账：将 Broker 上仍 connected 的设备同步到 DB/Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DeviceOnlineReconcileService {

    private final EmqxManagementClient emqxManagementClient;
    private final DeviceOnlineOfflineHandler onlineOfflineHandler;
    private final StringRedisTemplate redisTemplate;

    @Value("${mqtt.emqx.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    @Value("${mqtt.emqx.reconcile-lock-seconds:120}")
    private long reconcileLockSeconds;

    /**
     * ApplicationReady 后调用：单 Pod 持锁执行全量对账，失败不阻断启动。
     */
    public void reconcileOnStartup() {
        if (!reconcileEnabled) {
            log.info("[EmqxReconcile] reconcile-enabled=false，跳过启动对账");
            return;
        }
        if (!tryAcquireStartupLock()) {
            log.info("[EmqxReconcile] 其他节点正在执行启动对账，本节点跳过");
            return;
        }

        long started = System.currentTimeMillis();
        List<String> connectedCodes;
        try {
            connectedCodes = emqxManagementClient.listConnectedDeviceCodes();
        } catch (Exception e) {
            log.warn("[EmqxReconcile] 拉取 EMQX connected clients 失败，跳过对账", e);
            return;
        }

        AtomicInteger promoted = new AtomicInteger();
        AtomicInteger alreadyOnline = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (String deviceCode : connectedCodes) {
            ReconcileOnlineResult result = onlineOfflineHandler.reconcileOnline(deviceCode);
            switch (result) {
                case PROMOTED -> promoted.incrementAndGet();
                case ALREADY_ONLINE -> alreadyOnline.incrementAndGet();
                case FAILED -> failed.incrementAndGet();
                default -> skipped.incrementAndGet();
            }
        }

        log.info("[EmqxReconcile] 启动对账完成 connected={} promoted={} alreadyOnline={} skipped={} failed={} costMs={}",
                connectedCodes.size(), promoted.get(), alreadyOnline.get(), skipped.get(), failed.get(),
                System.currentTimeMillis() - started);
    }

    private boolean tryAcquireStartupLock() {
        String owner = resolveLockOwner();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                DeviceConstant.RedisKey.EMQX_STARTUP_RECONCILE_LOCK,
                owner,
                reconcileLockSeconds,
                TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private static String resolveLockOwner() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
