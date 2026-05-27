package org.jeecg.modules.device.service.impl.master;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MasterAuthValidateServiceTest {

    private Subject subject;
    private MasterAuthValidateService service;

    @BeforeEach
    void setUp() {
        subject = mock(Subject.class);
        ThreadContext.bind(subject);
        service = new MasterAuthValidateService(null, null, null);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unbindSubject();
    }

    @Test
    @DisplayName("bindOperatorFromLogin 使用 Shiro LoginUser 覆盖请求体 operatorId")
    void bindOperatorUsesLoginUser() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId("user-1");
        loginUser.setUsername("alice");
        loginUser.setRealname("Alice");
        when(subject.getPrincipal()).thenReturn(loginUser);

        MasterLoginDTO dto = new MasterLoginDTO();
        dto.setOperatorId("spoof-id");

        String operatorId = service.bindOperatorFromLogin(null, dto);

        assertThat(operatorId).isEqualTo("user-1");
        assertThat(dto.getOperatorId()).isEqualTo("user-1");
        assertThat(dto.getOperatorName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("请求体 operatorId 与登录用户不一致时拒绝")
    void bindOperatorRejectsMismatchedOperatorId() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId("user-1");
        when(subject.getPrincipal()).thenReturn(loginUser);

        MasterLoginDTO dto = new MasterLoginDTO();
        dto.setOperatorId("other-user");

        assertThatThrownBy(() -> service.bindOperatorFromLogin(null, dto))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("assertRobotAuthorized 拒绝未授权机器人")
    void assertRobotAuthorizedRejectsUnauthorizedRobot() {
        IotDevice robot = new IotDevice();
        robot.setId("robot-2");

        UsageStatusVO.RobotBasicVO allowed = new UsageStatusVO.RobotBasicVO();
        allowed.setRobotId("robot-1");

        assertThatThrownBy(() -> service.assertRobotAuthorized(robot, List.of(allowed)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("授权范围");
    }

    @Test
    @DisplayName("assertRobotAuthorized 允许授权列表内机器人")
    void assertRobotAuthorizedAllowsListedRobot() {
        IotDevice robot = new IotDevice();
        robot.setId("robot-1");

        UsageStatusVO.RobotBasicVO allowed = new UsageStatusVO.RobotBasicVO();
        allowed.setRobotId("robot-1");

        service.assertRobotAuthorized(robot, List.of(allowed));
    }
}
