package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * sys_depart 企业/子公司查询行（用于组装树）
 */
@Data
public class EnterpriseNodeRowDTO {
    private String id;
    private String parentId;
    private String name;
    /** 机构类别：1=公司，4=子公司 */
    private String orgCategory;
}

