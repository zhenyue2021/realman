package org.jeecg.modules.device.service.impl.master;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 主控登录授权校验：控制器合法性、操作员部门归属、设备授权有效性、可用机器人列表。
 */
@Service
@RequiredArgsConstructor
public class MasterAuthValidateService {

    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final SysAuthFeignClient sysAuthFeignClient;

    /**
     * 从 JWT / Shiro 会话绑定操作员，禁止信任请求体中的 operatorId。
     */
    public String bindOperatorFromLogin(HttpServletRequest request, MasterLoginDTO dto) {
        LoginUser loginUser = resolveLoginUser();
        if (dto.getOperatorId() != null && !dto.getOperatorId().isBlank()
                && !Objects.equals(dto.getOperatorId(), loginUser.getId())) {
            throw new RuntimeException("操作员ID与登录用户不一致");
        }
        dto.setOperatorId(loginUser.getId());
        if (dto.getOperatorName() == null || dto.getOperatorName().isBlank()) {
            dto.setOperatorName(oConvertUtils.isNotEmpty(loginUser.getRealname())
                    ? loginUser.getRealname()
                    : loginUser.getUsername());
        }
        return loginUser.getId();
    }

    /**
     * 校验工单解析出的机器人在当前主控授权范围内。
     */
    public void assertRobotAuthorized(IotDevice robot, List<UsageStatusVO.RobotBasicVO> availableRobots) {
        if (robot == null) {
            return;
        }
        if (availableRobots == null || availableRobots.isEmpty()) {
            throw new RuntimeException("无权限：当前主控无可用机器人授权");
        }
        boolean allowed = availableRobots.stream()
                .anyMatch(item -> Objects.equals(item.getRobotId(), robot.getId()));
        if (!allowed) {
            throw new RuntimeException("无权限：工单绑定的机器人不在当前主控授权范围内");
        }
    }

    private LoginUser resolveLoginUser() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        if (principal instanceof LoginUser loginUser
                && loginUser.getId() != null
                && !loginUser.getId().isBlank()) {
            return loginUser;
        }
        throw new RuntimeException("未登录或会话已失效");
    }

    /**
     * 通过设备编码查询主控设备，不存在或类型不符则抛异常。
     */
    public IotDevice lookupController(String deviceCode) {
        IotDevice controller = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, deviceCode)
                        .eq(IotDevice::getDeviceType, DEVICE_TYPE_CONTROLLER)
        );
        if (controller == null) {
            throw new RuntimeException("主控设备不存在或非主控设备: deviceCode=" + deviceCode);
        }
        return controller;
    }

    /**
     * 校验主控在线状态，离线则抛异常。
     */
    public void checkControllerOnline(IotDevice controller) {
        if (!Objects.equals(controller.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
            throw new RuntimeException("当前主控设备不在线");
        }
    }

    /**
     * 查询操作员所属部门列表，operatorId 为空或无部门时抛异常。
     */
    public List<String> lookupDepartIds(String operatorId) {
        if (operatorId == null || operatorId.isEmpty()) {
            throw new RuntimeException("缺少操作员ID（operatorId）");
        }
        List<String> departIds = sysAuthFeignClient.getDepartIdsByUserId(operatorId);
        if (departIds == null || departIds.isEmpty()) {
            throw new RuntimeException("无权限：当前用户未绑定企业");
        }
        return departIds;
    }

    /**
     * 查询主控对指定部门的有效授权，无授权则抛异常。
     */
    public List<IotDeviceAuth> queryAuths(IotDevice controller, List<String> departIds, LocalDateTime now) {
        LambdaQueryWrapper<IotDeviceAuth> wrapper = new LambdaQueryWrapper<IotDeviceAuth>()
                .eq(IotDeviceAuth::getControllerId, controller.getId())
                .eq(IotDeviceAuth::getStatus, 1)
                .and(w -> w.isNull(IotDeviceAuth::getEffectiveTime).or().le(IotDeviceAuth::getEffectiveTime, now))
                .and(w -> w.isNull(IotDeviceAuth::getExpireTime).or().ge(IotDeviceAuth::getExpireTime, now))
                .in(IotDeviceAuth::getEnterpriseId, departIds);
        List<IotDeviceAuth> auths = deviceAuthMapper.selectList(wrapper);
        if (auths == null || auths.isEmpty()) {
            throw new RuntimeException("无权限：主控设备未授权给当前用户所在企业");
        }
        return auths;
    }

    /**
     * 从授权列表中提取可用机器人列表。
     */
    public List<UsageStatusVO.RobotBasicVO> buildAvailableRobots(List<IotDeviceAuth> auths) {
        Set<String> allowedRobotIds = new HashSet<>();
        for (IotDeviceAuth a : auths) {
            if (a.getDeviceId() != null && !a.getDeviceId().isEmpty()) {
                allowedRobotIds.add(a.getDeviceId());
            }
        }
        List<UsageStatusVO.RobotBasicVO> available = new ArrayList<>();
        for (String rid : allowedRobotIds) {
            IotDevice robot = deviceMapper.selectById(rid);
            if (robot != null && Integer.valueOf(DEVICE_TYPE_ROBOT).equals(robot.getDeviceType())) {
                available.add(toRobotBasicVO(robot));
            }
        }
        return available;
    }

    /**
     * 将 IotDevice 转换为 RobotBasicVO。
     */
    public UsageStatusVO.RobotBasicVO toRobotBasicVO(IotDevice robot) {
        UsageStatusVO.RobotBasicVO v = new UsageStatusVO.RobotBasicVO();
        v.setRobotId(robot.getId());
        v.setRobotCode(robot.getDeviceCode());
        v.setRobotName(robot.getDeviceName());
        v.setStatus(robot.getStatus());
        v.setUseStatus(robot.getUseStatus());
        v.setDeviceModel(robot.getDeviceModel());
        v.setFirmwareVersion(robot.getFirmwareVersion());
        return v;
    }
}
