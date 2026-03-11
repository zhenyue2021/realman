package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;

import java.util.List;

public interface IWorkOrderAttachmentService extends IService<WorkOrderAttachment> {

    void addAttachments(String workOrderId, String createBy, List<WorkOrderAttachment> attachments);
}

