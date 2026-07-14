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

    /** 传输层成功（HTTP 200 + message=ok），业务成败由 data 内 bizCode 区分 */
    public static <T> ApiResult<T> transportOk(T data, boolean success) {
        return of(200, "ok", success, data);
    }
    private static <T> ApiResult<T> of(int code, String msg, boolean success, T data) {
        ApiResult<T> r = new ApiResult<>(); r.code=code; r.message=msg; r.success = success; r.data=data; return r;
    }
}
