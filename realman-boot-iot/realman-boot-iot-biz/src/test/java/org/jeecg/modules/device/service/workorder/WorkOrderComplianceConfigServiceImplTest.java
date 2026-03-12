package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.service.impl.workorder.WorkOrderComplianceConfigServiceImpl;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 工单合规配置 Service 单元测试
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderComplianceConfigServiceImplTest {

    @Mock
    private WorkOrderComplianceConfigMapper baseMapper;

    @InjectMocks
    private WorkOrderComplianceConfigServiceImpl configService;

    private WorkOrderComplianceConfig config;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(configService, "baseMapper", baseMapper);
        config = new WorkOrderComplianceConfig();
        config.setId("cfg-001");
        config.setAgentId("agent-1");
        config.setAgentName("测试代理商");
        config.setTaskName("巡检任务");
        config.setTaskDesc("每日巡检");
        config.setStatus(0);
        config.setDelFlag(0);
        config.setTimeoutAlertEnabled(1);
        config.setTimeoutAlertSeconds(1800);
        config.setSubmitLimitEnabled(1);
        config.setAutoCloseEnabled(0);
    }

    @Test
    @DisplayName("保存合规配置时调用 mapper.insert")
    void save_callsInsert() {
        config.setId(null);
        when(baseMapper.insert(any(WorkOrderComplianceConfig.class))).thenReturn(1);

        configService.save(config);

        ArgumentCaptor<WorkOrderComplianceConfig> captor = ArgumentCaptor.forClass(WorkOrderComplianceConfig.class);
        verify(baseMapper).insert(captor.capture());
        assertThat(captor.getValue().getTaskName()).isEqualTo("巡检任务");
        assertThat(captor.getValue().getTimeoutAlertSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("根据ID获取配置时调用 selectById")
    void getById_callsSelectById() {
        when(baseMapper.selectById("cfg-001")).thenReturn(config);

        WorkOrderComplianceConfig result = configService.getById("cfg-001");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("cfg-001");
        assertThat(result.getTaskName()).isEqualTo("巡检任务");
        verify(baseMapper).selectById("cfg-001");
    }

    @Test
    @DisplayName("分页查询带条件")
    void page_appliesWrapperAndReturnsPage() {
        Page<WorkOrderComplianceConfig> page = new Page<>(1, 10);
        when(baseMapper.selectPage(eq(page), any(LambdaQueryWrapper.class))).thenReturn(page);

        var wrapper = new LambdaQueryWrapper<WorkOrderComplianceConfig>()
                .eq(WorkOrderComplianceConfig::getDelFlag, 0)
                .orderByDesc(WorkOrderComplianceConfig::getCreateTime);
        var result = configService.page(page, wrapper);

        assertThat(result).isSameAs(page);
        verify(baseMapper).selectPage(eq(page), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("更新配置时调用 updateById")
    void updateById_callsUpdateById() {
        when(baseMapper.updateById(any(WorkOrderComplianceConfig.class))).thenReturn(1);

        config.setTaskDesc("更新后的描述");
        boolean ok = configService.updateById(config);

        assertThat(ok).isTrue();
        ArgumentCaptor<WorkOrderComplianceConfig> captor = ArgumentCaptor.forClass(WorkOrderComplianceConfig.class);
        verify(baseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTaskDesc()).isEqualTo("更新后的描述");
    }

    @Test
    @DisplayName("删除配置时调用 deleteById（逻辑删除由 MyBatis-Plus 处理）")
    void removeById_callsDeleteById() {
        when(baseMapper.deleteById(eq("cfg-001"))).thenReturn(1);

        boolean ok = configService.removeById("cfg-001");

        assertThat(ok).isTrue();
        verify(baseMapper).deleteById("cfg-001");
    }

    @Test
    @DisplayName("list 带条件返回列表")
    void list_returnsList() {
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));

        var wrapper = new LambdaQueryWrapper<WorkOrderComplianceConfig>()
                .eq(WorkOrderComplianceConfig::getStatus, 0)
                .eq(WorkOrderComplianceConfig::getDelFlag, 0);
        List<WorkOrderComplianceConfig> list = configService.list(wrapper);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTaskName()).isEqualTo("巡检任务");
    }
}
