package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceRoom;
import org.jeecg.modules.device.geo.AdministrativeAddressParser;
import org.jeecg.modules.device.mapper.IotDeviceRoomMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.impl.device.IotDeviceSupport;
import org.jeecg.modules.device.service.webrtc.RoomTurnRouteCache;
import org.jeecg.modules.device.service.webrtc.TurnRouteResult;
import org.jeecg.modules.device.service.webrtc.TurnRouterClient;
import org.jeecg.modules.device.service.webrtc.WebRtcEndpointAssembler;
import org.jeecg.modules.device.vo.DeviceRoomVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * IoT 设备房间服务实现
 *
 * <p>缓存策略：
 * <ul>
 *   <li>{@code iot:room:master:{masterCode}} → JSON(DeviceRoomVO)，TTL=24h</li>
 *   <li>{@code iot:room:robot:{robotCode}}   → masterCode 字符串，TTL=24h</li>
 *   <li>{@code iot:room:turn-route:{masterCode}} → JSON(RoomTurnRouteCache)，TTL=24h</li>
 *   <li>{@code iot:room:active}              → Set&lt;masterCode&gt;</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceRoomServiceImpl extends ServiceImpl<IotDeviceRoomMapper, IotDeviceRoom>
        implements IIotDeviceRoomService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TurnRouterClient turnRouterClient;
    private final WebRtcEndpointAssembler webRtcEndpointAssembler;
    private final IotDeviceSupport deviceSupport;

    private static final long ROOM_CACHE_TTL_HOURS = 24L;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MqttMessageModel.WebRtcCommand queryOrCreate(String masterCode, String robotCode) {
        if (masterCode == null || masterCode.isBlank()) {
            throw new RuntimeException("masterCode 不能为空");
        }
        if (robotCode == null || robotCode.isBlank()) {
            throw new RuntimeException("robotCode 不能为空");
        }

        IotDevice master = deviceSupport.requireByDeviceCode(masterCode);
        IotDevice robot = deviceSupport.requireByDeviceCode(robotCode);
        requireDeviceType(master, DeviceConstant.DeviceTypeInteger.CONTROLLER, "主控");
        requireDeviceType(robot, DeviceConstant.DeviceTypeInteger.ROBOT, "机器人");

        AdministrativeAddressParser.ProvinceCity browserLoc =
                AdministrativeAddressParser.parse(master.getAddress());
        AdministrativeAddressParser.ProvinceCity robotLoc =
                AdministrativeAddressParser.parse(robot.getAddress());

        IotDeviceRoom room = resolveRoom(masterCode, robotCode);
        String roomId = room.getId();

        RoomTurnRouteCache routeCache = getTurnRouteCache(masterCode);
        if (routeCache == null) {
            TurnRouteResult route = turnRouterClient.route(
                    roomId,
                    robotLoc.province(), robotLoc.city(),
                    browserLoc.province(), browserLoc.city());
            routeCache = new RoomTurnRouteCache(route.getServerIp(), route.getServerPort(), route.getSignalKey());
            putTurnRouteCache(masterCode, routeCache);
        }

        MqttMessageModel.WebRtcCommand startCmd = webRtcEndpointAssembler.assemble(routeCache);
        startCmd.setRoomId(roomId);
        startCmd.setTimestamp(System.currentTimeMillis());
        return startCmd;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void destroyRoom(String masterCode) {
        IotDeviceRoom room = baseMapper.selectActiveByMasterCode(masterCode);
        if (room == null) {
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
        String masterCode = redisTemplate.opsForValue()
                .get(DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + robotCode);
        if (masterCode != null && !masterCode.isBlank()) {
            destroyRoom(masterCode);
            return;
        }

        IotDeviceRoom room = baseMapper.selectActiveByRobotCode(robotCode);
        if (room != null) {
            destroyRoom(room.getMasterCode());
        }
    }

    @Override
    public List<DeviceRoomVO> listActiveRooms() {
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

        log.info("[Room] listActiveRooms 缓存未命中，降级查 DB");
        List<IotDeviceRoom> rooms = list(new LambdaQueryWrapper<IotDeviceRoom>()
                .eq(IotDeviceRoom::getStatus, IotDeviceRoom.Status.ACTIVE));

        List<DeviceRoomVO> result = new ArrayList<>(rooms.size());
        for (IotDeviceRoom room : rooms) {
            DeviceRoomVO vo = toVO(room);
            putCache(room.getMasterCode(), room.getRobotCode(), vo);
            result.add(vo);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    private IotDeviceRoom resolveRoom(String masterCode, String robotCode) {
        DeviceRoomVO cached = getFromCache(masterCode);
        if (cached != null) {
            IotDeviceRoom cachedRoom = baseMapper.selectActiveByMasterCode(masterCode);
            if (cachedRoom != null) {
                if (!Objects.equals(cachedRoom.getRobotCode(), robotCode)) {
                    return updateRoomRobot(cachedRoom, robotCode);
                }
                return cachedRoom;
            }
            log.warn("[Room] 缓存命中但 DB 记录不存在，清除脏缓存 masterCode={}", masterCode);
            evictCache(masterCode, cached.getRobotCode());
        }

        IotDeviceRoom room = baseMapper.selectActiveByMasterCode(masterCode);
        if (room == null) {
            room = new IotDeviceRoom();
            room.setMasterCode(masterCode);
            room.setRobotCode(robotCode);
            room.setStatus(IotDeviceRoom.Status.ACTIVE);
            save(room);
            log.info("[Room] 创建新房间 roomId={} master={} robot={}", room.getId(), masterCode, robotCode);
        } else if (!Objects.equals(room.getRobotCode(), robotCode)) {
            room = updateRoomRobot(room, robotCode);
        }

        DeviceRoomVO vo = toVO(room);
        scheduleCacheWrite(masterCode, robotCode, vo);
        return room;
    }

    private IotDeviceRoom updateRoomRobot(IotDeviceRoom room, String robotCode) {
        String oldRobotCode = room.getRobotCode();
        if (oldRobotCode != null && !oldRobotCode.isBlank() && !oldRobotCode.equals(robotCode)) {
            redisTemplate.delete(DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + oldRobotCode);
            evictTurnRouteCache(room.getMasterCode());
        }
        room.setRobotCode(robotCode);
        room.setStatus(IotDeviceRoom.Status.ACTIVE);
        updateById(room);
        log.info("[Room] 更新房间机器人 roomId={} master={} robot={}", room.getId(), room.getMasterCode(), robotCode);
        scheduleCacheWrite(room.getMasterCode(), robotCode, toVO(room));
        return room;
    }

    private void scheduleCacheWrite(String masterCode, String robotCode, DeviceRoomVO vo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    putCache(masterCode, robotCode, vo);
                }
            });
        } else {
            putCache(masterCode, robotCode, vo);
        }
    }

    private static void requireDeviceType(IotDevice device, int expectType, String label) {
        if (!Objects.equals(device.getDeviceType(), expectType)) {
            throw new RuntimeException("设备类型不匹配：[" + device.getDeviceCode() + "] 不是" + label + "设备");
        }
    }

    private RoomTurnRouteCache getTurnRouteCache(String masterCode) {
        try {
            String json = redisTemplate.opsForValue()
                    .get(DeviceConstant.RedisKey.ROOM_TURN_ROUTE_PREFIX + masterCode);
            if (json != null) {
                return objectMapper.readValue(json, RoomTurnRouteCache.class);
            }
        } catch (Exception e) {
            log.warn("[Room] TURN 路由缓存读取失败 masterCode={}", masterCode, e);
        }
        return null;
    }

    private void putTurnRouteCache(String masterCode, RoomTurnRouteCache cache) {
        try {
            String json = objectMapper.writeValueAsString(cache);
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.ROOM_TURN_ROUTE_PREFIX + masterCode,
                    json,
                    ROOM_CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[Room] TURN 路由缓存写入失败 masterCode={}", masterCode, e);
        }
    }

    private void evictTurnRouteCache(String masterCode) {
        redisTemplate.delete(DeviceConstant.RedisKey.ROOM_TURN_ROUTE_PREFIX + masterCode);
    }

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

    private void putCache(String masterCode, String robotCode, DeviceRoomVO vo) {
        try {
            String json = objectMapper.writeValueAsString(vo);
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.ROOM_MASTER_PREFIX + masterCode,
                    json,
                    ROOM_CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(DeviceConstant.RedisKey.ROOM_ACTIVE_SET, masterCode);
            if (robotCode != null && !robotCode.isBlank()) {
                redisTemplate.opsForValue().set(
                        DeviceConstant.RedisKey.ROOM_ROBOT_PREFIX + robotCode,
                        masterCode,
                        ROOM_CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("[Room] 缓存写入失败 masterCode={}", masterCode, e);
        }
    }

    private void evictCache(String masterCode, String robotCode) {
        redisTemplate.delete(DeviceConstant.RedisKey.ROOM_MASTER_PREFIX + masterCode);
        redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.ROOM_ACTIVE_SET, masterCode);
        evictTurnRouteCache(masterCode);
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
