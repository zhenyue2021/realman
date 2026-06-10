package org.jeecg.modules.device.util;

/**
 * 操作日志 {@code operation_detail} 字段 JSON 片段构建（与现有手写格式一致）。
 */
public final class OperationLogDetail {

    private OperationLogDetail() {}

    public static String ofCommand(String commandId, String topic) {
        return "{\"commandId\":\"" + escape(commandId) + "\",\"topic\":\"" + escape(topic) + "\"}";
    }

    public static String ofCommand(String commandId, String topic, Integer code) {
        String base = ofCommand(commandId, topic);
        if (code == null) {
            return base;
        }
        return base.substring(0, base.length() - 1) + ",\"code\":" + code + "}";
    }

    public static String ofRequest(String requestId, String topic) {
        return "{\"requestId\":\"" + escape(requestId) + "\",\"topic\":\"" + escape(topic) + "\"}";
    }

    public static String ofRequest(String requestId, String topic, Integer code) {
        String base = ofRequest(requestId, topic);
        if (code == null) {
            return base;
        }
        return base.substring(0, base.length() - 1) + ",\"code\":" + code + "}";
    }

    public static String ofTopic(String topic) {
        return "{\"topic\":\"" + escape(topic) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
