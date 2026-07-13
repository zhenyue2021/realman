package org.jeecg.modules.commhub.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WebhookDispatchResult {
    private boolean success;
    private Integer statusCode;
    private String errorMessage;
}
