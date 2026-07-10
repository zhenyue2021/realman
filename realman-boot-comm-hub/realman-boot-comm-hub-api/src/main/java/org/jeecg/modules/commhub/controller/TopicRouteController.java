package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.entity.CommHubTopicRoute;
import org.jeecg.modules.commhub.service.CommHubTopicRouteRegistry;
import org.jeecg.modules.commhub.util.RequestUtil;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备端向 MQTT Topic 路由注册表管理（运维/超管使用），对齐设备通信中台详细设计
 * 2.4、已知限制第 6 项——原 {@code MqttMessageDispatcher} 内硬编码 switch，现落库
 * 可经此接口配置。TOKEN_REFRESH/BRIDGE_ACK 两个 routeType 对应固定处理逻辑，仅
 * enabled/description 有实际意义，不可通过新增记录获得等价的新处理能力。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Topic 路由注册表管理", description = "设备端向 MQTT Topic -> 处理类别映射")
public class TopicRouteController {

    private final CommHubTopicRouteRegistry topicRouteRegistry;

    @GetMapping("/api/v1/topic-routes")
    @Operation(summary = "查询全部路由（含禁用项）")
    public Result<List<CommHubTopicRoute>> list() {
        return Result.ok(topicRouteRegistry.list());
    }

    @PostMapping("/api/v1/topic-routes")
    @RequiresPermissions("commHub:topicRoute:manage")
    @Operation(summary = "新增/编辑路由（topicSuffix 已存在则覆盖），立即生效")
    public Result<Void> upsert(@RequestBody CommHubTopicRoute route, HttpServletRequest httpRequest) {
        topicRouteRegistry.upsert(route, RequestUtil.safeUsername(httpRequest));
        return Result.ok();
    }

    @DeleteMapping("/api/v1/topic-routes")
    @RequiresPermissions("commHub:topicRoute:manage")
    @Operation(summary = "删除路由（对应 Topic 上报后将被忽略），立即生效；topicSuffix 含 '/'，用查询参数而非路径变量传递")
    public Result<Void> delete(@RequestParam String topicSuffix) {
        topicRouteRegistry.delete(topicSuffix);
        return Result.ok();
    }

    @PutMapping("/api/v1/topic-routes/reload")
    @RequiresPermissions("commHub:topicRoute:manage")
    @Operation(summary = "手动触发缓存刷新（正常情况下 upsert/delete 已自动刷新，此接口用于多实例部署下强制同步）")
    public Result<Void> reload() {
        topicRouteRegistry.reload();
        return Result.ok();
    }
}
