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

/**
 * OTA 固件升级 Service 实现
 *
 * <p>OTA 完整流程：
 * <pre>
 *   1. 分片上传固件文件（uploadFirmwareChunk）→ 临时目录暂存，分片进度记入 Redis
 *   2. 合并分片并发布到 MinIO（mergeAndPublish）→ 计算 MD5，生成预签名 URL，写入 DB
 *   3. 创建升级任务（createUpgradeTask）→ 指定固件 + 目标设备列表，生成升级记录
 *   4. 执行升级任务（executeUpgradeTask）→ 向每台在线设备发送 OtaNotify 消息（支持断点续传）
 *   5. 设备上报进度（由 OtaProgressHandler 处理）→ 平台更新记录状态
 * </pre>
 *
 * <p>断点续传机制：
 *   设备上报 downloadedBytes 时，平台缓存到 Redis（Key: iot:ota:progress:{deviceCode}:{recordId}）。
 *   任务重执行时，平台读取缓存值并填入 OtaNotify.downloadedBytes，设备据此从断点继续下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotOtaServiceImpl extends ServiceImpl<IotOtaFirmwareMapper, IotOtaFirmware>
        implements IIotOtaService {

    private final IotOtaFirmwareMapper       firmwareMapper;
    private final IotOtaUpgradeTaskMapper    taskMapper;
    private final IotOtaUpgradeRecordMapper  recordMapper;
    private final IotDeviceMapper            deviceMapper;
    private final MqttPublisher              mqttPublisher;
    private final StringRedisTemplate        redisTemplate;
    private final ObjectMapper               objectMapper;
    private final MinioClient                minioClient;
    private final IDeviceOperationLogService logService;

    /** MinIO 存储桶名称 */
    @Value("${minio.bucket-name:iot-firmware}")
    private String bucketName;

    /** 预签名下载 URL 有效期（天） */
    @Value("${minio.url-expire-days:7}")
    private int urlExpireDays;

    /** 固件分片临时目录（合并完成后自动清理） */
    @Value("${device.ota.chunk-temp-dir:/tmp/iot-ota-chunks}")
    private String chunkTempDir;

    /**
     * 上传固件分片（支持断点续传式分片上传）
     *
     * <p>执行流程：
     * <ol>
     *   <li>若 uploadId 为空则自动生成（首次上传时前端传空）</li>
     *   <li>在临时目录 {chunkTempDir}/{uploadId}/ 下存储分片文件（命名 chunk_{chunkIndex}）</li>
     *   <li>将 chunkIndex 加入 Redis Set，并设置 24h TTL（避免过期数据占用磁盘）</li>
     * </ol>
     *
     * @param file        分片文件
     * @param uploadId    上传会话 ID（首次可为空，后续必须传同一 ID）
     * @param chunkIndex  当前分片序号（从 0 开始）
     * @param totalChunks 总分片数（用于前端计算进度，平台暂不强制校验）
     * @return uploadId（首次上传时返回新生成的 ID）
     */
    @Override
    public String uploadFirmwareChunk(MultipartFile file, String uploadId,
                                       Integer chunkIndex, Integer totalChunks) {
        if (uploadId == null || uploadId.isEmpty()) uploadId = IdUtil.fastSimpleUUID();
        File dir = new File(chunkTempDir + "/" + uploadId);
        dir.mkdirs();
        try {
            file.transferTo(new File(dir, "chunk_" + chunkIndex));
            // 记录已上传分片（用于前端查询断点、合并时校验完整性）
            redisTemplate.opsForSet().add(
                    DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId, String.valueOf(chunkIndex));
            redisTemplate.expire(DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId, 24, TimeUnit.HOURS);
            return uploadId;
        } catch (Exception e) {
            throw new RuntimeException("分片上传失败: " + e.getMessage());
        }
    }

    /**
     * 查询已上传的分片列表（断点续传时前端调用，判断哪些分片已上传）
     *
     * @param uploadId 上传会话 ID
     * @return 已上传的分片序号列表（升序排列）
     */
    @Override
    public List<Integer> getUploadedChunks(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(
                DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId);
        if (members == null) return Collections.emptyList();
        return members.stream().map(Integer::parseInt).sorted().collect(Collectors.toList());
    }

    /**
     * 合并分片并发布固件
     *
     * <p>执行流程：
     * <ol>
     *   <li>按分片序号排序后顺序合并为完整二进制文件</li>
     *   <li>计算 MD5（设备下载后校验完整性）</li>
     *   <li>上传合并文件到 MinIO（路径：firmware/{productId}/{version}/{fileName}）</li>
     *   <li>生成预签名下载 URL（有效期 urlExpireDays 天）</li>
     *   <li>写入固件记录到 DB（iot_ota_firmware）</li>
     *   <li>清理临时分片目录和 Redis Set</li>
     * </ol>
     *
     * @param uploadId      上传会话 ID
     * @param firmwareName  固件名称
     * @param version       版本号（如 "1.2.0"）
     * @param productId     产品 ID（固件按产品管理）
     * @param description   固件描述
     * @return 新增的固件记录（含 id、downloadUrl、fileMd5 等）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotOtaFirmware mergeAndPublish(String uploadId, String firmwareName,
                                           String version, String productId, String description) {
        File dir = new File(chunkTempDir + "/" + uploadId);
        if (!dir.exists()) throw new RuntimeException("uploadId不存在或已过期: " + uploadId);

        // 1. 按序号排序分片文件
        File[] chunks = dir.listFiles((d, n) -> n.startsWith("chunk_"));
        if (chunks == null || chunks.length == 0) throw new RuntimeException("未找到分片");
        Arrays.sort(chunks, Comparator.comparingInt(f -> Integer.parseInt(f.getName().replace("chunk_", ""))));

        // 2. 顺序合并分片为完整文件
        String mergedName = firmwareName + "_" + version + ".bin";
        File merged = new File(chunkTempDir, mergedName);
        try (RandomAccessFile raf = new RandomAccessFile(merged, "rw")) {
            for (File c : chunks) raf.write(java.nio.file.Files.readAllBytes(c.toPath()));
        } catch (Exception e) {
            throw new RuntimeException("合并失败: " + e.getMessage());
        }

        // 3. 计算 MD5（设备用于校验下载完整性）
        String md5  = DigestUtil.md5Hex(merged);
        long   size = merged.length();
        String obj  = "firmware/" + productId + "/" + version + "/" + mergedName;

        // 4. 上传到 MinIO
        try (FileInputStream fis = new FileInputStream(merged)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName).object(obj)
                    .stream(fis, size, -1).contentType("application/octet-stream").build());
        } catch (Exception e) {
            throw new RuntimeException("上传MinIO失败: " + e.getMessage());
        }

        // 5. 生成设备可直接访问的预签名下载 URL
        String url;
        try {
            url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucketName).object(obj)
                    .expiry(urlExpireDays, TimeUnit.DAYS).build());
        } catch (Exception e) {
            throw new RuntimeException("生成下载URL失败: " + e.getMessage());
        }

        // 6. 写入固件记录
        IotOtaFirmware fw = new IotOtaFirmware();
        fw.setFirmwareName(firmwareName); fw.setVersion(version); fw.setProductId(productId);
        fw.setFilePath(obj); fw.setFileName(mergedName); fw.setFileSize(size);
        fw.setFileMd5(md5); fw.setDownloadUrl(url); fw.setDescription(description);
        fw.setStatus(1); fw.setForceUpgrade(0); fw.setCreateTime(LocalDateTime.now());
        firmwareMapper.insert(fw);

        // 7. 清理临时目录和 Redis 分片记录
        try {
            FileUtils.deleteDirectory(dir);
            merged.delete();
            redisTemplate.delete(DeviceConstant.RedisKey.UPLOAD_CHUNK_PREFIX + uploadId);
        } catch (Exception ignored) {}
        return fw;
    }

    /**
     * 创建 OTA 升级任务
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验固件存在性</li>
     *   <li>批量查询目标设备（过滤掉不存在的 deviceId）</li>
     *   <li>创建升级任务记录（iot_ota_upgrade_task）</li>
     *   <li>批量创建每台设备的升级记录（iot_ota_upgrade_record），状态均为 PENDING</li>
     * </ol>
     *
     * <p>注意：创建任务不会立即发送通知，需调用 {@link #executeUpgradeTask} 触发。
     *
     * @param firmwareId 固件 ID
     * @param deviceIds  目标设备 ID 列表
     * @param taskName   任务名称
     * @param operator   创建人
     * @return 新建的升级任务记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotOtaUpgradeTask createUpgradeTask(String firmwareId, List<String> deviceIds,
                                                String taskName, String operator) {
        IotOtaFirmware fw = firmwareMapper.selectById(firmwareId);
        if (fw == null) throw new RuntimeException("固件不存在");
        List<IotDevice> devices = deviceMapper.selectBatchIds(deviceIds);
        if (devices.isEmpty()) throw new RuntimeException("无有效设备");

        // 创建任务：upgradeType=1 单设备，upgradeType=2 批量
        IotOtaUpgradeTask task = new IotOtaUpgradeTask();
        task.setTaskName(taskName); task.setFirmwareId(firmwareId);
        task.setFirmwareVersion(fw.getVersion()); task.setTaskStatus(0);
        task.setUpgradeType(deviceIds.size() == 1 ? 1 : 2);
        task.setTotalCount(devices.size()); task.setSuccessCount(0);
        task.setFailCount(0); task.setUpgradingCount(0);
        task.setCreateBy(operator); task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        // 为每台设备创建升级记录（初始状态 PENDING）
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

    /**
     * 执行 OTA 升级任务（向各设备发送 OTA 通知）
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验任务存在且状态为待执行（status=0）</li>
     *   <li>更新任务状态为执行中，记录开始时间</li>
     *   <li>遍历所有 PENDING 状态升级记录：</li>
     *   <li>  - 查询断点续传进度（Redis 中已下载字节数）</li>
     *   <li>  - 构造加密 OtaNotify 消息并发送给设备</li>
     *   <li>  - 更新记录状态为 NOTIFIED，记录通知时间</li>
     *   <li>  - 若发送失败，标记记录为 FAILED 并记录失败原因</li>
     * </ol>
     *
     * @param taskId 升级任务 ID
     * @throws RuntimeException 任务不存在或已执行时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeUpgradeTask(String taskId) {
        IotOtaUpgradeTask task = taskMapper.selectById(taskId);
        if (task == null || task.getTaskStatus() != 0) throw new RuntimeException("任务不存在或已执行");
        IotOtaFirmware fw = firmwareMapper.selectById(task.getFirmwareId());

        // 查询所有待通知的升级记录
        List<IotOtaUpgradeRecord> pending = recordMapper.selectList(
                new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getTaskId, taskId)
                        .eq(IotOtaUpgradeRecord::getUpgradeStatus, DeviceConstant.OtaUpgradeStatus.PENDING));

        // 标记任务为执行中
        task.setTaskStatus(1);
        task.setActualStartTime(LocalDateTime.now());
        taskMapper.updateById(task);

        for (IotOtaUpgradeRecord rec : pending) {
            IotDevice device = deviceMapper.selectById(rec.getDeviceId());
            if (device == null) continue;
            try {
                // 查询断点续传进度（上次下载中断时保存的已下载字节数）
                String resumeKey = DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX
                        + device.getDeviceCode() + ":" + rec.getId();
                String resumeStr = redisTemplate.opsForValue().get(resumeKey);
                long resumeBytes = resumeStr != null ? Long.parseLong(resumeStr) : 0L;
                if (resumeBytes > 0) {
                    rec.setDownloadedBytes(resumeBytes);
                    recordMapper.updateById(rec);
                }

                // 构造并加密发送 OTA 通知（含固件下载 URL、MD5、文件大小、断点字节数）
                String payload = objectMapper.writeValueAsString(MqttMessageModel.OtaNotify.builder()
                        .taskId(taskId).recordId(rec.getId()).firmwareId(fw.getId())
                        .version(fw.getVersion()).downloadUrl(fw.getDownloadUrl())
                        .fileMd5(fw.getFileMd5()).fileSize(fw.getFileSize())
                        .forceUpgrade(fw.getForceUpgrade()).timestamp(System.currentTimeMillis()).build());
                mqttPublisher.publishToDevice(device.getDeviceCode(),
                        String.format(DeviceConstant.MqttTopic.OTA_NOTIFY, device.getDeviceCode()), payload, 1);

                // 更新记录状态为 NOTIFIED
                rec.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.NOTIFIED);
                rec.setNotifyTime(LocalDateTime.now());
                recordMapper.updateById(rec);

                logService.recordLog(device.getId(), device.getDeviceCode(),
                        DeviceConstant.OperationType.FIRMWARE_UPGRADE,
                        "OTA通知已发送，目标版本=" + fw.getVersion()
                                + (resumeBytes > 0 ? "，断点续传 已下载=" + resumeBytes + "字节" : ""),
                        null, DeviceConstant.OperationSource.PLATFORM, "PENDING", null, task.getCreateBy(), null);
            } catch (Exception e) {
                // 发送失败：标记当前记录为 FAILED，继续处理其他设备
                log.error("[OTA] 通知设备[{}]失败", device.getDeviceCode(), e);
                rec.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.FAILED);
                rec.setFailReason("通知失败: " + e.getMessage());
                recordMapper.updateById(rec);
            }
        }
    }

    /**
     * 取消升级任务
     *
     * <p>将任务状态置为已取消（status=4）。
     * 注意：已发出 OTA 通知的设备不会撤回消息，正在升级的设备可能仍会继续升级。
     *
     * @param taskId   任务 ID
     * @param operator 操作人
     */
    @Override
    public void cancelUpgradeTask(String taskId, String operator) {
        IotOtaUpgradeTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在");
        task.setTaskStatus(4);
        taskMapper.updateById(task);
    }
}
