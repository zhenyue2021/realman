package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.mapper.workorder.WorkOrderAttachmentMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderAttachmentServiceImpl
        extends ServiceImpl<WorkOrderAttachmentMapper, WorkOrderAttachment>
        implements IWorkOrderAttachmentService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addAttachments(String workOrderId, String createBy, List<WorkOrderAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (WorkOrderAttachment a : attachments) {
            a.setId(null);
            a.setWorkOrderId(workOrderId);
            a.setCreateBy(createBy);
            a.setCreateTime(now);
            this.baseMapper.insert(a);
        }
    }
}

