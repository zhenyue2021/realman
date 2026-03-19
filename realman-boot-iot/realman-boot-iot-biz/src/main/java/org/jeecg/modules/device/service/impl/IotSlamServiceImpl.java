package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.SlamBindingPageQueryDTO;
import org.jeecg.modules.device.dto.SlamMapDetailDTO;
import org.jeecg.modules.device.dto.SlamMapPageQueryDTO;
import org.jeecg.modules.device.dto.SlamSyncTaskDetailDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.entity.IotSlamSyncTask;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotRobotSlamBindingMapper;
import org.jeecg.modules.device.mapper.IotSlamMapMapper;
import org.jeecg.modules.device.mapper.IotSlamSyncTaskMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IIotSlamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotSlamServiceImpl implements IIotSlamService {

    private final IotSlamMapMapper slamMapMapper;
    private final IotRobotSlamBindingMapper bindingMapper;
    private final IotSlamSyncTaskMapper taskMapper;
    private final IotDeviceMapper deviceMapper;
    private final MinioClient minioClient;
    private final MqttPublisher mqttPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${minio.bucket-name.slam:iot-slam}")
    private String bucketName;

    @Override
    public IPage<IotSlamMap> pageMaps(String tenantId, SlamMapPageQueryDTO query) {
        int pageNo = query.getPageNo() == null ? 1 : query.getPageNo();
        int pageSize = query.getPageSize() == null ? 10 : query.getPageSize();
        LambdaQueryWrapper<IotSlamMap> wrapper = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(IotSlamMap::getTenantId, tenantId);
        }
        if (query.getSourceRobotId() != null && !query.getSourceRobotId().isBlank()) {
            wrapper.eq(IotSlamMap::getSourceRobotId, query.getSourceRobotId());
        }
        if (query.getMapName() != null && !query.getMapName().isBlank()) {
            wrapper.like(IotSlamMap::getMapName, query.getMapName());
        }
        if (query.getStatus() != null) {
            wrapper.eq(IotSlamMap::getStatus, query.getStatus());
        }
        wrapper.eq(IotSlamMap::getDelFlag, 0).orderByDesc(IotSlamMap::getCreateTime);
        return slamMapMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    @Override
    public SlamMapDetailDTO mapDetail(String mapId) {
        IotSlamMap map = slamMapMapper.selectById(mapId);
        if (map == null || (map.getDelFlag() != null && map.getDelFlag() == 1)) {
            throw new RuntimeException("SLAM地图不存在");
        }
        List<IotRobotSlamBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getSlamMapId, mapId)
                .eq(IotRobotSlamBinding::getDelFlag, 0)
                .orderByDesc(IotRobotSlamBinding::getCreateTime));
        SlamMapDetailDTO dto = new SlamMapDetailDTO();
        dto.setMap(map);
        dto.setBindings(bindings);
        return dto;
    }

    @Override
    public IPage<IotRobotSlamBinding> pageBindings(String tenantId, SlamBindingPageQueryDTO query) {
        int pageNo = query.getPageNo() == null ? 1 : query.getPageNo();
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        LambdaQueryWrapper<IotRobotSlamBinding> wrapper = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(IotRobotSlamBinding::getTenantId, tenantId);
        }
        if (query.getRobotId() != null && !query.getRobotId().isBlank()) {
            wrapper.eq(IotRobotSlamBinding::getRobotId, query.getRobotId());
        }
        if (query.getSlamMapId() != null && !query.getSlamMapId().isBlank()) {
            wrapper.eq(IotRobotSlamBinding::getSlamMapId, query.getSlamMapId());
        }
        if (query.getState() != null) {
            wrapper.eq(IotRobotSlamBinding::getState, query.getState());
        }
        wrapper.eq(IotRobotSlamBinding::getDelFlag, 0).orderByDesc(IotRobotSlamBinding::getCreateTime);
        return bindingMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String startSync(String tenantId, String operator, String enterpriseId, String sourceRobotId, String slamMapId, List<String> targetRobotIds) {
        if (sourceRobotId == null || sourceRobotId.isBlank() || slamMapId == null || slamMapId.isBlank()) {
            throw new RuntimeException("sourceRobotId与slamMapId不能为空");
        }
        if (targetRobotIds == null || targetRobotIds.isEmpty()) {
            throw new RuntimeException("targetRobotIds不能为空");
        }
        IotSlamMap map = slamMapMapper.selectById(slamMapId);
        if (map == null || !Objects.equals(map.getStatus(), DeviceConstant.SlamMapStatus.READY)) {
            throw new RuntimeException("SLAM地图不存在或未就绪");
        }
        IotDevice source = deviceMapper.selectById(sourceRobotId);
        if (source == null || !Objects.equals(source.getDeviceType(), 1)) {
            throw new RuntimeException("来源机器人不存在");
        }
        IotSlamSyncTask task = new IotSlamSyncTask();
        task.setTenantId(tenantId);
        task.setEnterpriseId(enterpriseId);
        task.setSourceRobotId(source.getId());
        task.setSourceRobotCode(source.getDeviceCode());
        task.setSlamMapId(slamMapId);
        task.setTargetRobotIds(toJson(targetRobotIds));
        task.setTotalCount(targetRobotIds.size());
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setStatus(DeviceConstant.SlamSyncTaskStatus.RUNNING);
        task.setCreateBy(operator);
        taskMapper.insert(task);

        String getUrl = presignGetUrl(map.getFileObjectKey(), DeviceConstant.Timeout.SLAM_UPLOAD_PERMIT_MINUTES);
        for (String robotId : targetRobotIds) {
            IotDevice target = deviceMapper.selectById(robotId);
            if (target == null || !Objects.equals(target.getDeviceType(), 1)) {
                continue;
            }
            IotRobotSlamBinding pending = new IotRobotSlamBinding();
            pending.setTenantId(tenantId);
            pending.setEnterpriseId(enterpriseId);
            pending.setRobotId(target.getId());
            pending.setRobotCode(target.getDeviceCode());
            pending.setSlamMapId(slamMapId);
            pending.setState(DeviceConstant.SlamBindingState.PENDING);
            pending.setPendingTaskId(task.getId());
            pending.setCreateBy(operator);
            bindingMapper.insert(pending);

            if (Objects.equals(target.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
                try {
                    MqttMessageModel.SlamSyncCommand cmd = MqttMessageModel.SlamSyncCommand.builder()
                            .taskId(task.getId())
                            .bindingId(pending.getId())
                            .slamMapId(slamMapId)
                            .sourceRobotCode(source.getDeviceCode())
                            .objectKey(map.getFileObjectKey())
                            .getUrl(getUrl)
                            .md5(map.getFileMd5())
                            .size(map.getFileSize())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    mqttPublisher.publishToDevice(target.getDeviceCode(),
                            String.format(DeviceConstant.MqttTopic.SLAM_SYNC_COMMAND, target.getDeviceCode()),
                            objectMapper.writeValueAsString(cmd), 1);
                } catch (Exception e) {
                    log.warn("[SLAM] 下发同步命令失败 taskId={}, robotCode={}, err={}",
                            task.getId(), target.getDeviceCode(), e.getMessage());
                }
            }
        }
        return task.getId();
    }

    @Override
    public SlamSyncTaskDetailDTO taskDetail(String taskId) {
        IotSlamSyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("同步任务不存在");
        }
        List<IotRobotSlamBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getPendingTaskId, taskId)
                .eq(IotRobotSlamBinding::getDelFlag, 0)
                .orderByDesc(IotRobotSlamBinding::getCreateTime));
        SlamSyncTaskDetailDTO dto = new SlamSyncTaskDetailDTO();
        dto.setTask(task);
        dto.setBindings(bindings);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MqttMessageModel.SlamUploadPermit handleUploadRequest(String deviceCode, MqttMessageModel.SlamUploadRequest req) {
        IotDevice robot = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .eq(IotDevice::getDeviceType, 1)
                .eq(IotDevice::getDelFlag, 0)
                .last("limit 1"));
        if (robot == null) {
            throw new RuntimeException("机器人不存在: " + deviceCode);
        }
        String ext = req.getExt() == null || req.getExt().isBlank() ? ".zip" : req.getExt();
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        String mapName = req.getMapName() == null || req.getMapName().isBlank() ? "slam_map" : req.getMapName();
        String objectKey = "slam/" + robot.getTenantId() + "/" + robot.getDeviceCode() + "/" +
                System.currentTimeMillis() + "_" + mapName.replaceAll("\\s+", "_") + ext;

        IotSlamMap map = new IotSlamMap();
        map.setTenantId(robot.getTenantId() == null ? null : String.valueOf(robot.getTenantId()));
        map.setMapName(mapName);
        map.setMapVersion(req.getMapVersion());
        map.setSourceRobotId(robot.getId());
        map.setSourceRobotCode(robot.getDeviceCode());
        map.setFileObjectKey(objectKey);
        map.setFileMd5(req.getMd5());
        map.setFileSize(req.getSize());
        map.setStatus(DeviceConstant.SlamMapStatus.UPLOADING);
        slamMapMapper.insert(map);

        String requestId = req.getRequestId() == null || req.getRequestId().isBlank()
                ? String.valueOf(System.currentTimeMillis()) : req.getRequestId();
        String sessionKey = DeviceConstant.RedisKey.SLAM_UPLOAD_SESSION_PREFIX + deviceCode + ":" + requestId;
        redisTemplate.opsForValue().set(sessionKey, map.getId(), DeviceConstant.Timeout.SLAM_UPLOAD_PERMIT_MINUTES, TimeUnit.MINUTES);

        return MqttMessageModel.SlamUploadPermit.builder()
                .requestId(requestId)
                .mapId(map.getId())
                .objectKey(objectKey)
                .putUrl(presignPutUrl(objectKey, DeviceConstant.Timeout.SLAM_UPLOAD_PERMIT_MINUTES))
                .expireAt(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DeviceConstant.Timeout.SLAM_UPLOAD_PERMIT_MINUTES))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleUploadComplete(String deviceCode, MqttMessageModel.SlamUploadComplete req) {
        String mapId = req.getMapId();
        if ((mapId == null || mapId.isBlank()) && req.getRequestId() != null && !req.getRequestId().isBlank()) {
            String key = DeviceConstant.RedisKey.SLAM_UPLOAD_SESSION_PREFIX + deviceCode + ":" + req.getRequestId();
            mapId = redisTemplate.opsForValue().get(key);
        }
        if (mapId == null || mapId.isBlank()) {
            log.warn("[SLAM] 上传完成但未定位地图记录 deviceCode={}, req={}", deviceCode, req);
            return;
        }
        IotSlamMap map = slamMapMapper.selectById(mapId);
        if (map == null) {
            return;
        }
        if (req.getCode() != null && req.getCode() != 0) {
            map.setStatus(DeviceConstant.SlamMapStatus.DELETED);
            slamMapMapper.updateById(map);
            return;
        }
        map.setStatus(DeviceConstant.SlamMapStatus.READY);
        if (req.getMd5() != null && !req.getMd5().isBlank()) {
            map.setFileMd5(req.getMd5());
        }
        if (req.getSize() != null && req.getSize() > 0) {
            map.setFileSize(req.getSize());
        }
        slamMapMapper.updateById(map);

        bindingMapper.update(null, new LambdaUpdateWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getRobotId, map.getSourceRobotId())
                .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.ACTIVE)
                .eq(IotRobotSlamBinding::getDelFlag, 0)
                .set(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.OBSOLETE)
                .set(IotRobotSlamBinding::getObsoleteTime, LocalDateTime.now()));

        IotRobotSlamBinding sourceActive = new IotRobotSlamBinding();
        sourceActive.setTenantId(map.getTenantId());
        sourceActive.setEnterpriseId(map.getEnterpriseId());
        sourceActive.setRobotId(map.getSourceRobotId());
        sourceActive.setRobotCode(map.getSourceRobotCode());
        sourceActive.setSlamMapId(map.getId());
        sourceActive.setState(DeviceConstant.SlamBindingState.ACTIVE);
        sourceActive.setEffectiveTime(LocalDateTime.now());
        sourceActive.setCreateBy("device:" + deviceCode);
        bindingMapper.insert(sourceActive);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSyncAck(String deviceCode, MqttMessageModel.SlamSyncAck ack) {
        if (ack.getBindingId() == null || ack.getBindingId().isBlank()) {
            return;
        }
        IotRobotSlamBinding pending = bindingMapper.selectById(ack.getBindingId());
        if (pending == null || !Objects.equals(pending.getState(), DeviceConstant.SlamBindingState.PENDING)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (ack.getCode() != null && ack.getCode() == 0) {
            bindingMapper.update(null, new LambdaUpdateWrapper<IotRobotSlamBinding>()
                    .eq(IotRobotSlamBinding::getRobotId, pending.getRobotId())
                    .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.ACTIVE)
                    .eq(IotRobotSlamBinding::getDelFlag, 0)
                    .set(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.OBSOLETE)
                    .set(IotRobotSlamBinding::getObsoleteTime, now));
            pending.setState(DeviceConstant.SlamBindingState.ACTIVE);
            pending.setEffectiveTime(now);
            pending.setFailReason(null);
        } else {
            pending.setState(DeviceConstant.SlamBindingState.FAILED);
            pending.setFailReason(ack.getMessage());
        }
        bindingMapper.updateById(pending);
        refreshTaskStatus(pending.getPendingTaskId());
    }

    private void refreshTaskStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        IotSlamSyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Long pending = bindingMapper.selectCount(new LambdaQueryWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getPendingTaskId, taskId)
                .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.PENDING)
                .eq(IotRobotSlamBinding::getDelFlag, 0));
        Long success = bindingMapper.selectCount(new LambdaQueryWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getPendingTaskId, taskId)
                .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.ACTIVE)
                .eq(IotRobotSlamBinding::getDelFlag, 0));
        Long fail = bindingMapper.selectCount(new LambdaQueryWrapper<IotRobotSlamBinding>()
                .eq(IotRobotSlamBinding::getPendingTaskId, taskId)
                .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.FAILED)
                .eq(IotRobotSlamBinding::getDelFlag, 0));
        task.setSuccessCount(success.intValue());
        task.setFailCount(fail.intValue());
        if (pending > 0) {
            task.setStatus(DeviceConstant.SlamSyncTaskStatus.RUNNING);
        } else if (fail == 0) {
            task.setStatus(DeviceConstant.SlamSyncTaskStatus.SUCCESS);
        } else if (success == 0) {
            task.setStatus(DeviceConstant.SlamSyncTaskStatus.FAIL);
        } else {
            task.setStatus(DeviceConstant.SlamSyncTaskStatus.PARTIAL_FAIL);
        }
        taskMapper.updateById(task);
    }

    private String presignPutUrl(String objectKey, long minutes) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucketName)
                    .object(objectKey)
                    .expiry((int) minutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("生成SLAM上传URL失败: " + e.getMessage(), e);
        }
    }

    private String presignGetUrl(String objectKey, long minutes) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectKey)
                    .expiry((int) minutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("生成SLAM下载URL失败: " + e.getMessage(), e);
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}

