package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 轻量分页结果，供契约模块使用，避免把 MyBatis-Plus {@code IPage} 这类持久层类型
 * 暴露到跨服务契约里。
 */
@Data
@NoArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> records = Collections.emptyList();

    private long total;

    private long pageNo;

    private long pageSize;

    public PageResult(List<T> records, long total, long pageNo, long pageSize) {
        this.records = records;
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }
}
