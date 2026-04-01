package org.jeecg.modules.device.constant;

/**
 *  MQTT 常量定义
 */
public interface MqttConstant {

    /**
     * QoS Level
     * QoS---0，At most once，至多一次；
     * QoS---1，At least once，至少一次；
     * QoS---2，Exactly once，确保只有一次。
     */
    interface MQTT_QOS {
        int QOS_0 = 0;
        int QOS_1 = 1;
        int QOS_2 = 2;
    }

}
