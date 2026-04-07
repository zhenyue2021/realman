package org.jeecg.modules.device.controller;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IoT API 全局异常：避免将未预期的异常信息（如 JDBC、堆栈细节）直接透出给客户端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 未预期异常（多为受检 Exception 或基础设施故障）对客户端的固定提示，不返回 e.getMessage() */
    @Value("${iot.api.generic-error-message:服务繁忙，请稍后重试}")
    private String genericErrorMessage;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ApiResult.fail(msg);
    }

    @ExceptionHandler(JeecgBootException.class)
    public ApiResult<Void> handleJeecgBoot(JeecgBootException e) {
        log.warn("[GlobalException] JeecgBootException: {}", e.getMessage());
        return ApiResult.fail(e.getMessage());
    }

    @ExceptionHandler(JeecgBootBizTipException.class)
    public ApiResult<Void> handleJeecgBootBizTip(JeecgBootBizTipException e) {
        log.warn("[GlobalException] JeecgBootBizTipException: {}", e.getMessage());
        return ApiResult.fail(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[GlobalException] IllegalArgumentException: {}", e.getMessage());
        String msg = e.getMessage();
        return ApiResult.fail(msg != null && !msg.isBlank() ? msg : genericErrorMessage);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResult<Void> handleIllegalState(IllegalStateException e) {
        log.warn("[GlobalException] IllegalStateException: {}", e.getMessage());
        String msg = e.getMessage();
        return ApiResult.fail(msg != null && !msg.isBlank() ? msg : genericErrorMessage);
    }

    /**
     * 业务层大量使用 RuntimeException 传递中文提示（如设备域），仍返回 message；
     * message 为空时退回泛化文案，避免 NPE 或空白提示。
     */
    @ExceptionHandler(RuntimeException.class)
    public ApiResult<Void> handleRuntime(RuntimeException e) {
        log.error("[GlobalException] RuntimeException", e);
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return ApiResult.fail(genericErrorMessage);
        }
        return ApiResult.fail(msg);
    }

    /**
     * 受检异常及未单独声明的运行时之外的异常：不向客户端返回 e.getMessage()，防止泄露内部实现细节。
     */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception e) {
        log.error("[GlobalException] unexpected", e);
        return ApiResult.fail(genericErrorMessage);
    }
}
