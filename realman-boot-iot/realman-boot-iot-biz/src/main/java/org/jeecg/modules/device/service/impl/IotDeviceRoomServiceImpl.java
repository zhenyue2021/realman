package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceRoom;
import org.jeecg.modules.device.mapper.IotDeviceRoomMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.signaling.SignalingKeyService;
import org.jeecg.modules.device.vo.DeviceRoomVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * IoT 设备房间服务实现
 *
 * <p>缓存策略：
 * <ul>
 *   <li>{@code iot:room:master:{masterCode}} → JSON(DeviceRoomVO)，TTL=24h，主查询路径</li>
 *   <li>{@code iot:room:robot:{robotCode}}   → masterCode 字符串，TTL=24h，离线时反查</li>
 *   <li>{@code iot:room:active}              → Set&lt;masterCode&gt;，用于列表查询</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceRoomServiceImpl extends ServiceImpl<IotDeviceRoomMapper, IotDeviceRoom>
        implements IIotDeviceRoomService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SignalingKeyService signalingKeyService;
    private final WebRtcProperties webRtcProperties;

    private static final long ROOM_CACHE_TTL_HOURS = 24L;

    // -------------------------------------------------------------------------
    // 公开方法
    // -------------------------------------------------------------------------

    @Override
    public  MqttMessageModel.WebRtcStartCommand queryOrCreate(String masterCode) {
        // 构建 TURN 服务器列表
        List<MqttMessageModel.WebRtcStartCommand.TurnServer> turnServers =
                webRtcProperties.getTurnServers().stream()
                        .map(t -> MqttMessageModel.WebRtcStartCommand.TurnServer.builder()
                                .url(t.getUrl())
                                .username(t.getUsername())
                                .password(t.getPassword())
                                .build())
                        .toList();

        MqttMessageModel.WebRtcStartCommand startCmd = MqttMessageModel.WebRtcStartCommand.builder()
                .signalUrl(signalingKeyService.getServerUrl())
                .signalKey(signalingKeyService.getCurrentKey())
                .turnServers(turnServers)
                .stunServers(webRtcProperties.getStunServerList())
                .timestamp(System.currentTimeMillis())
                .build();
        // 1. 缓存命中
        DeviceRoomVO cached = getFromCache(masterCode);
        if (cached != null) {
            startCmd.setRoomId(cached.getRoomId());
            return startCmd;
        }

        // 2. DB 查询活跃房间
        IotDeviceRoom room = baseMapper.selectActiveByMasterCode(masterCode);
        if (room == null) {
            // 3. 不存在则创建
            room = new IotDeviceRoom();
            room.setMasterCode(masterCode);
            room.setStatus(IotDeviceRoom.Status.WAITING);
            save(room);
            log.info("[Room] 创建新房间 roomId={} masterCode={}", room.getId(), masterCode);
        }

        DeviceRoomVO vo = toVO(room);
        putCache(masterCode, vo);
        startCmd.setRoomId(room.getId());
        return startCmd;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void robotJoin(String masterCode, String robotCode) {
        IotDeviceRoom room = baseMapper.selectActiveByMasterCode(masterCode);
        if (room == null) {
            log.warn("[Room] robotJoin 时找不到活跃房间 masterCode={}", masterCode);
            return;
        }

        room.setRobotCode(robotCode);
        room.setStatus(IotDeviceRoom.Status.ACTIVE);
        updateById(room);

        // 刷新主控缓存
        putCache(masterCode, toVO(room));
        // 建立机器人反查索引
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + robotCode,
                masterCode,
                ROOM_CACHE_TTL_HOURS, TimeUnit.HOURS);

        log.info("[Room] 机器人加入房间 roomId={} master={} robot={}", room.getId(), masterCode, robotCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void destroyRoom(String masterCode) {
        IotDeviceRoom room = baseMapper.selectActiveByMasterCode(masterCode);
        if (room == null) {
            // 房间不存在或已销毁，只清缓存即可
            evictCache(masterCode, null);
            return;
        }

        room.setStatus(IotDeviceRoom.Status.DESTROYED);
        room.setDestroyTime(LocalDateTime.now());
        room.setDelFlag(1);
        updateById(room);

        evictCache(masterCode, room.getRobotCode());
        log.info("[Room] 房间已销毁 roomId={} master={} robot={}", room.getId(), masterCode, room.getRobotCode());
    }

    @Override
    public void destroyRoomByRobotCode(String robotCode) {
        // 1. 缓存反查 masterCode
        String masterCode = redisTemplate.opsForValue()
                .get(DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + robotCode);
        if (masterCode != null && !masterCode.isBlank()) {
            destroyRoom(masterCode);
            return;
        }

        // 2. 降级走 DB
        IotDeviceRoom room = baseMapper.selectActiveByRobotCode(robotCode);
        if (room != null) {
            destroyRoom(room.getMasterCode());
        }
    }

    @Override
    public List<DeviceRoomVO> listActiveRooms() {
        // 1. 从活跃集合取所有 masterCode
        Set<String> masterCodes = redisTemplate.opsForSet()
                .members(DeviceConstant.RedisKey.ROOM_ACTIVE_SET);

        if (masterCodes != null && !masterCodes.isEmpty()) {
            List<DeviceRoomVO> result = new ArrayList<>(masterCodes.size());
            for (String code : masterCodes) {
                DeviceRoomVO vo = getFromCache(code);
                if (vo != null) {
                    result.add(vo);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 2. 降级：DB 查询并重建缓存
        log.info("[Room] listActiveRooms 缓存未命中，降级查 DB");
        List<IotDeviceRoom> rooms = list(new LambdaQueryWrapper<IotDeviceRoom>()
                .in(IotDeviceRoom::getStatus, IotDeviceRoom.Status.WAITING, IotDeviceRoom.Status.ACTIVE));

        List<DeviceRoomVO> result = new ArrayList<>(rooms.size());
        for (IotDeviceRoom room : rooms) {
            DeviceRoomVO vo = toVO(room);
            putCache(room.getMasterCode(), vo);
            result.add(vo);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    private DeviceRoomVO getFromCache(String masterCode) {
        try {
            String json = redisTemplate.opsForValue()
                    .get(DeviceConstant.RedisKey.ROOM_MASTER_PREFIX + masterCode);
            if (json != null) {
                return objectMapper.readValue(json, DeviceRoomVO.class);
            }
        } catch (Exception e) {
            log.warn("[Room] 缓存读取失败 masterCode={}", masterCode, e);
        }
        return null;
    }

    private void putCache(String masterCode, DeviceRoomVO vo) {
        try {
            String json = objectMapper.writeValueAsString(vo);
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.ROOM_MASTER_PREFIX + masterCode,
                    json,
                    ROOM_CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(DeviceConstant.RedisKey.ROOM_ACTIVE_SET, masterCode);
        } catch (Exception e) {
            log.warn("[Room] 缓存写入失败 masterCode={}", masterCode, e);
        }
    }

    private void evictCache(String masterCode, String robotCode) {
        redisTemplate.delete(DeviceConstant.RedisKey.ROOM_MASTER_PREFIX + masterCode);
        redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.ROOM_ACTIVE_SET, masterCode);
        if (robotCode != null && !robotCode.isBlank()) {
            redisTemplate.delete(DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + robotCode);
        }
    }

    private static DeviceRoomVO toVO(IotDeviceRoom room) {
        DeviceRoomVO vo = new DeviceRoomVO();
        vo.setRoomId(room.getId());
        vo.setMasterCode(room.getMasterCode());
        vo.setRobotCode(room.getRobotCode());
        vo.setStatus(room.getStatus());
        vo.setCreateTime(room.getCreateTime());
        return vo;
    }
}
