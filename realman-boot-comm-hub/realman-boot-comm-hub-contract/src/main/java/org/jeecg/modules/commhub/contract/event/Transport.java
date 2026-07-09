package org.jeecg.modules.commhub.contract.event;

/**
 * {@link DeviceUplinkEvent} 的原始传输介质。绝大多数事件恒为 {@code MQTT}；
 * 只有设备上电自注册（南向唯一 HTTP 例外）取值 {@code HTTP}。
 * 见设备通信中台详细设计 5.1。
 */
public enum Transport {
    MQTT,
    HTTP
}
