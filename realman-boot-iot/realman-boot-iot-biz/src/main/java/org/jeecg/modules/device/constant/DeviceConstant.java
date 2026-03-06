package org.jeecg.modules.device.constant;

public interface DeviceConstant {
    interface DeviceStatus { int INACTIVE=0,ONLINE=1,OFFLINE=2,DISABLED=3; }

    interface MqttTopic {
        String STATUS_REPORT  = "device/%s/status/report";
        String CONFIG_ACK     = "device/%s/config/ack";
        String RESTART_ACK    = "device/%s/command/restart/ack";
        String OTA_PROGRESS   = "device/%s/ota/progress";
        String OPERATION_LOG  = "device/%s/log/operation";
        String CONFIG_PUSH    = "device/%s/config/push";
        String REMOTE_RESTART = "device/%s/command/restart";
        String OTA_NOTIFY     = "device/%s/ota/notify";
        String SYS_CONNECTED    = "$SYS/brokers/+/clients/+/connected";
        String SYS_DISCONNECTED = "$SYS/brokers/+/clients/+/disconnected";
    }

    interface OtaUpgradeStatus {
        int PENDING=0,NOTIFIED=1,CONFIRMED=2,DOWNLOADING=3,
            DOWNLOADED=4,INSTALLING=5,SUCCESS=6,FAILED=7,TIMEOUT=8;
    }

    interface ConfigSyncStatus { int PENDING=0,SUCCESS=1,FAILED=2; }

    interface OperationType {
        String PARAM_MODIFY="PARAM_MODIFY", FIRMWARE_UPGRADE="FIRMWARE_UPGRADE",
               REMOTE_RESTART="REMOTE_RESTART", DEVICE_ONLINE="DEVICE_ONLINE",
               DEVICE_OFFLINE="DEVICE_OFFLINE", DEVICE_REGISTER="DEVICE_REGISTER",
               COMMAND_SEND="COMMAND_SEND", TOKEN_REFRESH="SECRET_RESET";
    }

    interface OperationSource { String PLATFORM="PLATFORM", DEVICE="DEVICE"; }

    interface RedisKey {
        String DEVICE_STATUS_PREFIX = "iot:device:status:";
        String DEVICE_SECRET_PREFIX = "iot:device:secret:";
        String DEVICE_ONLINE_SET    = "iot:device:online";
        String OTA_PROGRESS_PREFIX  = "iot:ota:progress:";
        String CONFIG_SYNC_PREFIX   = "iot:config:sync:";
        String UPLOAD_CHUNK_PREFIX  = "iot:upload:chunk:";
    }

    interface Timeout {
        long OTA_UPGRADE_TIMEOUT_MINUTES      = 30L;
        long CONFIG_SYNC_TIMEOUT_SECONDS      = 30L;
        long DEVICE_OFFLINE_THRESHOLD_MINUTES = 5L;
    }
}
