package org.jeecg.modules.device.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.*;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotOtaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotOtaServiceImpl extends ServiceImpl<IotOtaFirmwareMapper, IotOtaFirmware>
        implements IIotOtaService {

    private final IotOtaFirmwareMapper      firmwareMapper;
    private final IotOtaUpgradeTaskMapper   taskMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    private final IotDeviceMapper           deviceMapper;
    private final MqttPublisher             mqttPublisher;
    private final StringRedisTemplate       redisTemplate;
    private final ObjectMapper              objectMapper;
    private final MinioClient               minioClient;
    private final IDeviceOperationLogService logService;

    @Value("${minio.bucket-name:iot-firmware}")
    private String bucketName;
    @Value("${minio.url-expire-days:7}")
    private int urlExpireDays;
    @Value("${device.ota.chunk-temp-dir:/tmp/iot-ota-chunks}")
    private String chunkTempDir;

    @Override
    public String uploadFirmwareChunk(MultipartFile file, String uploadId,
                                       Integer chunkIndex, Integer totalChunks) {
        if (uploadId == null || uploadId.isEmpty()) uploadId = IdUtil.fastSimpleUUID();
        File dir = new File(chunkTempDir + "/" + uploadId);
        dir.mkdirs();
        try {
            file.transferTo(new File(dir, "chunk_" + chunkIndex));
            redisTemplate.opsForSet().add(
                    DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId, String.valueOf(chunkIndex));
            redisTemplate.expire(DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId, 24, TimeUnit.HOURS);
            return uploadId;
        } catch (Exception e) { throw new RuntimeException("分片上传失败: " + e.getMessage()); }
    }

    @Override
    public List<Integer> getUploadedChunks(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(
                DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId);
        if (members == null) return Collections.emptyList();
        return members.stream().map(Integer::parseInt).sorted().collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotOtaFirmware mergeAndPublish(String uploadId, String firmwareName,
                                           String version, String productId, String description) {
        File dir = new File(chunkTempDir + "/" + uploadId);
        if (!dir.exists()) throw new RuntimeException("uploadId不存在或已过期: " + uploadId);
        File[] chunks = dir.listFiles((d, n) -> n.startsWith("chunk_"));
        if (chunks == null || chunks.length == 0) throw new RuntimeException("未找到分片");
        Arrays.sort(chunks, Comparator.comparingInt(f -> Integer.parseInt(f.getName().replace("chunk_", ""))));

        String mergedName = firmwareName + "_" + version + ".bin";
        File merged = new File(chunkTempDir, mergedName);
        try (RandomAccessFile raf = new RandomAccessFile(merged, "rw")) {
            for (File c : chunks) raf.write(java.nio.file.Files.readAllBytes(c.toPath()));
        } catch (Exception e) { throw new RuntimeException("合并失败: " + e.getMessage()); }

        String md5  = DigestUtil.md5Hex(merged);
        long   size = merged.length();
        String obj  = "firmware/" + productId + "/" + version + "/" + mergedName;
        try (FileInputStream fis = new FileInputStream(merged)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName).object(obj)
                    .stream(fis, size, -1).contentType("application/octet-stream").build());
        } catch (Exception e) { throw new RuntimeException("上传MinIO失败: " + e.getMessage()); }

        String url;
        try {
            url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucketName).object(obj)
                    .expiry(urlExpireDays, TimeUnit.DAYS).build());
        } catch (Exception e) { throw new RuntimeException("生成下载URL失败: " + e.getMessage()); }

        IotOtaFirmware fw = new IotOtaFirmware();
        fw.setFirmwareName(firmwareName); fw.setVersion(version); fw.setProductId(productId);
        fw.setFilePath(obj); fw.setFileName(mergedName); fw.setFileSize(size);
        fw.setFileMd5(md5); fw.setDownloadUrl(url); fw.setDescription(description);
        fw.setStatus(1); fw.setForceUpgrade(0); fw.setCreateTime(LocalDateTime.now());
        firmwareMapper.insert(fw);

        try { FileUtils.deleteDirectory(dir); merged.delete();
            redisTemplate.delete(DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId);
        } catch (Exception ignored) {}
        return fw;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotOtaUpgradeTask createUpgradeTask(String firmwareId, List<String> deviceIds,
                                                String taskName, String operator) {
        IotOtaFirmware fw = firmwareMapper.selectById(firmwareId);
        if (fw == null) throw new RuntimeException("固件不存在");
        List<IotDevice> devices = deviceMapper.selectBatchIds(deviceIds);
        if (devices.isEmpty()) throw new RuntimeException("无有效设备");

        IotOtaUpgradeTask task = new IotOtaUpgradeTask();
        task.setTaskName(taskName); task.setFirmwareId(firmwareId);
        task.setFirmwareVersion(fw.getVersion()); task.setTaskStatus(0);
        task.setUpgradeType(deviceIds.size() == 1 ? 1 : 2);
        task.setTotalCount(devices.size()); task.setSuccessCount(0);
        task.setFailCount(0); task.setUpgradingCount(0);
        task.setCreateBy(operator); task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        List<IotOtaUpgradeRecord> records = devices.stream().map(d -> {
            IotOtaUpgradeRecord r = new IotOtaUpgradeRecord();
            r.setTaskId(task.getId()); r.setDeviceId(d.getId()); r.setDeviceCode(d.getDeviceCode());
            r.setFirmwareId(firmwareId); r.setOldVersion(d.getFirmwareVersion());
            r.setTargetVersion(fw.getVersion()); r.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.PENDING);
            r.setDownloadProgress(0); r.setDownloadedBytes(0L); r.setRetryCount(0);
            r.setCreateTime(LocalDateTime.now());
            return r;
        }).collect(Collectors.toList());
        recordMapper.batchInsert(records);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeUpgradeTask(String taskId) {
        IotOtaUpgradeTask task = taskMapper.selectById(taskId);
        if (task == null || task.getTaskStatus() != 0) throw new RuntimeException("任务不存在或已执行");
        IotOtaFirmware fw = firmwareMapper.selectById(task.getFirmwareId());
        List<IotOtaUpgradeRecord> pending = recordMapper.selectList(
                new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getTaskId, taskId)
                        .eq(IotOtaUpgradeRecord::getUpgradeStatus, DeviceConstant.OtaUpgradeStatus.PENDING));

        task.setTaskStatus(1); task.setActualStartTime(LocalDateTime.now());
        taskMapper.updateById(task);

        for (IotOtaUpgradeRecord rec : pending) {
            IotDevice device = deviceMapper.selectById(rec.getDeviceId());
            if (device == null) continue;
            try {
                // 断点续传：查已下载字节数
                String resumeKey = DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + device.getDeviceCode() + ":" + rec.getId();
                String resumeStr = redisTemplate.opsForValue().get(resumeKey);
                long resumeBytes = resumeStr != null ? Long.parseLong(resumeStr) : 0L;
                if (resumeBytes > 0) { rec.setDownloadedBytes(resumeBytes); recordMapper.updateById(rec); }

                String payload = objectMapper.writeValueAsString(MqttMessageModel.OtaNotify.builder()
                        .taskId(taskId).recordId(rec.getId()).firmwareId(fw.getId())
                        .version(fw.getVersion()).downloadUrl(fw.getDownloadUrl())
                        .fileMd5(fw.getFileMd5()).fileSize(fw.getFileSize())
                        .forceUpgrade(fw.getForceUpgrade()).timestamp(System.currentTimeMillis()).build());

                mqttPublisher.publishToDevice(device.getDeviceCode(),
                        String.format(DeviceConstant.MqttTopic.OTA_NOTIFY, device.getDeviceCode()), payload, 1);
                rec.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.NOTIFIED);
                rec.setNotifyTime(LocalDateTime.now());
                recordMapper.updateById(rec);

                logService.recordLog(device.getId(), device.getDeviceCode(),
                        DeviceConstant.OperationType.FIRMWARE_UPGRADE,
                        "OTA通知已发送，目标版本=" + fw.getVersion()
                                + (resumeBytes > 0 ? "，断点续传 已下载=" + resumeBytes : ""),
                        null, DeviceConstant.OperationSource.PLATFORM, "PENDING", null, task.getCreateBy(), null);
            } catch (Exception e) {
                log.error("[OTA] 通知设备[{}]失败", device.getDeviceCode(), e);
                rec.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.FAILED);
                rec.setFailReason("通知失败: " + e.getMessage());
                recordMapper.updateById(rec);
            }
        }
    }

    @Override
    public void cancelUpgradeTask(String taskId, String operator) {
        IotOtaUpgradeTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在");
        task.setTaskStatus(4);
        taskMapper.updateById(task);
    }
}
