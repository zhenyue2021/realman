package org.jeecg.modules.device.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.mapper.IotSlamMapMapper;
import org.jeecg.modules.device.service.IIotSlamMapService;
import org.jeecg.modules.device.util.MinioUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotSlamMapServiceImpl extends ServiceImpl<IotSlamMapMapper, IotSlamMap>
        implements IIotSlamMapService {

    private final MinioClient minioClient;
    private final MinioUtil minioUtil;

    @Value("${minio.bucket-name.slam:iot-slam}")
    private String bucketName;

    /** 预签名 URL 有效天数（与 minio.url-expire-days 一致） */
    @Value("${minio.url-expire-days:7}")
    private int urlExpireDays;

    /** URL 剩余有效期低于此阈值（分钟）时自动刷新 */
    private static final long REFRESH_THRESHOLD_MINUTES = 60L;

    @Override
    @Async("deviceTaskExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void processGetCurrentMap(IotSlamCommandRecord record) {
        if (record.getAckDataJson() == null) {
            log.warn("[SlamMap] ackDataJson 为空，跳过地图处理: commandId={}", record.getCommandId());
            return;
        }
        try {
            JSONObject data = JSONObject.parseObject(record.getAckDataJson());
            JSONObject png  = data.getJSONObject("png");
            JSONObject yaml = data.getJSONObject("yaml");
            JSONObject meta = data.getJSONObject("meta");

            if (png == null || png.getString("content") == null) {
                log.warn("[SlamMap] PNG 数据缺失，跳过: commandId={}", record.getCommandId());
                return;
            }

            // 1. 解码 base64 → 字节流
            byte[] pngBytes = Base64.getDecoder().decode(png.getString("content"));
            String filename  = png.getString("filename");
            String mimeType  = png.getString("mime_type");
            int    fileSize  = png.getIntValue("byte_size");

            // 2. 上传至 MinIO
            String objectKey = "slam-maps/" + record.getRobotCode() + "/" + record.getCommandId() + "/" + filename;
            minioUtil.ensureBucketExists(bucketName);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(pngBytes), pngBytes.length, -1)
                    .contentType(mimeType)
                    .build());
            log.info("[SlamMap] PNG 已上传 MinIO: key={}", objectKey);

            // 3. 生成预签名 GET URL
            long expireMinutes = (long) urlExpireDays * 24 * 60;
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry((int) expireMinutes, TimeUnit.MINUTES)
                            .build());

            // 4. 查询上一个有效版本（逻辑删除前），计算新版本号
            IotSlamMap prev = this.getOne(new LambdaQueryWrapper<IotSlamMap>()
                    .eq(IotSlamMap::getRobotCode, record.getRobotCode())
                    .orderByDesc(IotSlamMap::getCreateTime)
                    .last("LIMIT 1"));
            String newVersion = nextVersion(prev == null ? null : prev.getMapVersion());

            // 5. 构建新记录
            IotSlamMap map = new IotSlamMap();
            map.setRobotCode(record.getRobotCode());
            map.setMasterCode(record.getMasterCode());
            map.setCommandId(record.getCommandId());
            map.setFilename(filename);
            map.setMapVersion(newVersion);
            map.setMimeType(mimeType);
            map.setFileSize(fileSize);
            map.setMinioPath(objectKey);
            map.setPresignedUrl(presignedUrl);
            map.setPresignedUrlExpireTime(LocalDateTime.now().plusDays(urlExpireDays));
            if (yaml != null) {
                map.setYamlContent(yaml.getString("content"));
            }
            if (meta != null) {
                map.setMapName(meta.getString("map_name"));
                map.setResolution(meta.getDouble("resolution"));
                map.setWidth(meta.getInteger("width"));
                map.setHeight(meta.getInteger("height"));
            }

            // 6. 逻辑删除同机器人的历史地图（先写新记录、再删旧记录，防止新记录写失败时历史丢失）
            this.save(map);
            this.update(new LambdaUpdateWrapper<IotSlamMap>()
                    .eq(IotSlamMap::getRobotCode, record.getRobotCode())
                    .ne(IotSlamMap::getId, map.getId())
                    .set(IotSlamMap::getIsDeleted, 1));

            log.info("[SlamMap] 地图记录已保存: id={}, robotCode={}, mapName={}",
                    map.getId(), map.getRobotCode(), map.getMapName());

        } catch (Exception e) {
            log.error("[SlamMap] GetCurrentMap 处理失败: commandId={}", record.getCommandId(), e);
        }
    }

    /**
     * 根据上一个版本号计算下一个版本号。
     * 格式：V{major}.{minor}.{patch}，每次成功上传 patch +1。
     * 若从未有版本则返回 V1.0.0。
     */
    private static String nextVersion(String current) {
        if (current == null || current.isBlank()) {
            return "V1.0.0";
        }
        try {
            String digits = current.startsWith("V") ? current.substring(1) : current;
            String[] parts = digits.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return "V" + major + "." + minor + "." + (patch + 1);
        } catch (Exception e) {
            log.warn("[SlamMap] 版本号解析失败，重置为 V1.0.0: current={}", current);
            return "V1.0.0";
        }
    }

    @Override
    public IotSlamMap getCurrentMap(String robotCode) {
        IotSlamMap map = this.getOne(new LambdaQueryWrapper<IotSlamMap>()
                .eq(IotSlamMap::getRobotCode, robotCode)
                .orderByDesc(IotSlamMap::getCreateTime)
                .last("LIMIT 1"));

        if (map == null) {
            return null;
        }

        // 预签名 URL 剩余有效期不足阈值时自动刷新
        if (map.getPresignedUrlExpireTime() == null
                || map.getPresignedUrlExpireTime().isBefore(
                        LocalDateTime.now().plusMinutes(REFRESH_THRESHOLD_MINUTES))) {
            try {
                long expireMinutes = (long) urlExpireDays * 24 * 60;
                String newUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(map.getMinioPath())
                                .expiry((int) expireMinutes, TimeUnit.MINUTES)
                                .build());
                map.setPresignedUrl(newUrl);
                map.setPresignedUrlExpireTime(LocalDateTime.now().plusDays(urlExpireDays));
                this.updateById(map);
                log.info("[SlamMap] 预签名 URL 已刷新: robotCode={}", robotCode);
            } catch (Exception e) {
                log.warn("[SlamMap] 预签名 URL 刷新失败，返回旧 URL: robotCode={}", robotCode, e);
            }
        }

        return map;
    }
}
