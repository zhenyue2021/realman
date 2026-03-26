package org.jeecg.modules.device.vo;

import lombok.Data;

@Data
public class ApiResult<T> {
    private int code;
    private String message;
    private boolean success;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        return of(200, "success", true,  data);
    }
    public static <T> ApiResult<T> ok(T data, String msg) {
        return of(200, msg, true,  data);
    }
    public static <T> ApiResult<T> fail(String msg) {
        return of(500, msg, false,null);
    }
    private static <T> ApiResult<T> of(int code, String msg, boolean success, T data) {
        ApiResult<T> r = new ApiResult<>(); r.code=code; r.message=msg; r.success = success; r.data=data; return r;
    }
}
