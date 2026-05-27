package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.dto.mqtt.StartCollectCmd;
import org.jeecg.modules.device.datacollect.dto.mqtt.StopCollectCmd;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Darwin 工单采集指令与设备绑定（MQTT 下发 start/stop collect）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderDarwinCollectService {

    private final IotDeviceMapper iotDeviceMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;

    @Autowired(required = false)
    private DataCollectCommandService dataCollectCommandService;

    public void bindDarwinDevices(String workOrderId, String controllerCode, String robotCode) {
        List<WorkOrderDevice> devices = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        IotDevice controller = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, controllerCode));
        if (controller != null) {
            WorkOrderDevice wd = new WorkOrderDevice();
            wd.setWorkOrderId(workOrderId);
            wd.setDeviceCode(controller.getDeviceCode());
            wd.setDeviceId(controller.getId());
            wd.setDeviceName(controller.getDeviceName());
            wd.setDeviceType(DeviceConstant.DeviceType.CONTROLLER);
            wd.setCreateTime(now);
            devices.add(wd);
        } else {
            log.warn("[Darwin] 主控设备不存在，跳过绑定 controllerCode={} workOrderId={}", controllerCode, workOrderId);
        }

        if (robotCode != null) {
            IotDevice robot = iotDeviceMapper.selectOne(
                    new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, robotCode));
            if (robot != null) {
                WorkOrderDevice wd = new WorkOrderDevice();
                wd.setWorkOrderId(workOrderId);
                wd.setDeviceCode(robot.getDeviceCode());
                wd.setDeviceId(robot.getId());
                wd.setDeviceName(robot.getDeviceName());
                wd.setDeviceType(DeviceConstant.DeviceType.ROBOT);
                wd.setCreateTime(now);
                devices.add(wd);
            } else {
                log.warn("[Darwin] 机器人设备不存在，跳过绑定 robotCode={} workOrderId={}", robotCode, workOrderId);
            }
        }

        if (!devices.isEmpty()) {
            workOrderDeviceMapper.delete(new LambdaQueryWrapper<WorkOrderDevice>()
                    .eq(WorkOrderDevice::getWorkOrderId, workOrderId));
            for (WorkOrderDevice d : devices) {
                d.setId(null);
                d.setWorkOrderId(workOrderId);
                workOrderDeviceMapper.insert(d);
            }
        }
    }

    public void sendStartCollect(String workOrderId, WorkOrder order, String robotCode, String operatorName) {
        if (dataCollectCommandService == null) {
            return;
        }
        try {
            StartCollectCmd.CollectParams params = StartCollectCmd.CollectParams.builder()
                    .primarySceneEn(order.getLevel1SceneNameEn())
                    .secondarySceneEn(order.getLevel2SceneNameEn())
                    .collectionItemNameEn(order.getCollectionItemNameEn())
                    .operatorName(operatorName)
                    .tenantId(order.getTenantId())
                    .build();
            dataCollectCommandService.sendStartCollect(robotCode, workOrderId, params);
            log.info("[Darwin] 开始采集指令已下发 workOrderId={} robotCode={}", workOrderId, robotCode);
        } catch (Exception e) {
            log.warn("[Darwin] 开始采集指令下发失败 workOrderId={} robotCode={}", workOrderId, robotCode, e);
        }
    }

    public void sendStopCollect(String workOrderId, WorkOrder order) {
        if (dataCollectCommandService == null) {
            return;
        }
        WorkOrderDevice robotDevice = workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                        .in(WorkOrderDevice::getDeviceType,
                                List.of(DeviceConstant.DeviceType.ROBOT, "ROBOT"))
                        .last("LIMIT 1"));
        if (robotDevice == null || robotDevice.getDeviceCode() == null) {
            log.warn("[Darwin] 提交工单时未找到机器人设备，跳过停止采集 workOrderId={}", workOrderId);
            return;
        }
        try {
            StopCollectCmd.CollectParams params = StopCollectCmd.CollectParams.builder()
                    .primarySceneEn(order.getLevel1SceneNameEn())
                    .secondarySceneEn(order.getLevel2SceneNameEn())
                    .collectionItemNameEn(order.getCollectionItemNameEn())
                    .operatorName(order.getOperatorName())
                    .tenantId(order.getTenantId())
                    .build();
            dataCollectCommandService.sendStopCollect(robotDevice.getDeviceCode(), workOrderId, params);
            log.info("[Darwin] 停止采集指令已下发 workOrderId={} robotCode={}", workOrderId, robotDevice.getDeviceCode());
        } catch (Exception e) {
            log.warn("[Darwin] 停止采集指令下发失败 workOrderId={}", workOrderId, e);
        }
    }
}
