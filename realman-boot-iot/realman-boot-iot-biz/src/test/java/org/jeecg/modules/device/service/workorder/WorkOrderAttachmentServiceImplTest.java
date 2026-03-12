package org.jeecg.modules.device.service.workorder;

import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.mapper.workorder.WorkOrderAttachmentMapper;
import org.jeecg.modules.device.service.impl.workorder.WorkOrderAttachmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 工单附件 Service 单元测试
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderAttachmentServiceImplTest {

    @Mock
    private WorkOrderAttachmentMapper baseMapper;

    @InjectMocks
    private WorkOrderAttachmentServiceImpl attachmentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(attachmentService, "baseMapper", baseMapper);
    }

    @Test
    @DisplayName("addAttachments：空列表不插入")
    void addAttachments_emptyList_noInsert() {
        attachmentService.addAttachments("wo-001", "admin", List.of());
        attachmentService.addAttachments("wo-001", "admin", null);

        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("addAttachments：批量插入并设置 workOrderId、createBy、createTime")
    void addAttachments_insertsWithWorkOrderAndCreator() {
        WorkOrderAttachment a = new WorkOrderAttachment();
        a.setFileName("pic.png");
        a.setFileUrl("https://example.com/pic.png");
        a.setDescription("佐证");
        when(baseMapper.insert(any(WorkOrderAttachment.class))).thenReturn(1);

        attachmentService.addAttachments("wo-001", "user1", List.of(a));

        ArgumentCaptor<WorkOrderAttachment> captor = ArgumentCaptor.forClass(WorkOrderAttachment.class);
        verify(baseMapper).insert(captor.capture());
        WorkOrderAttachment saved = captor.getValue();
        assertThat(saved.getWorkOrderId()).isEqualTo("wo-001");
        assertThat(saved.getCreateBy()).isEqualTo("user1");
        assertThat(saved.getFileName()).isEqualTo("pic.png");
        assertThat(saved.getCreateTime()).isNotNull();
    }
}
