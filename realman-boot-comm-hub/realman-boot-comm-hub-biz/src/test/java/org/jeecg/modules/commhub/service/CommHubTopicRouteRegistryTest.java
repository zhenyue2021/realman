package org.jeecg.modules.commhub.service;

import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.entity.CommHubTopicRoute;
import org.jeecg.modules.commhub.mapper.CommHubTopicRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Topic 路由从硬编码 switch 落库可配置后的核心行为：未注册/禁用的 Topic 一律
 * 忽略（不是报错，避免设备端上报未知 Topic 时打断处理）、routeType 白名单校验
 * （避免管理端录入拼写错误后静默不生效）、写操作立即刷新缓存生效。
 */
@ExtendWith(MockitoExtension.class)
class CommHubTopicRouteRegistryTest {

    @Mock
    private CommHubTopicRouteMapper topicRouteMapper;

    private CommHubTopicRouteRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommHubTopicRouteRegistry(topicRouteMapper);
    }

    private CommHubTopicRoute route(String topicSuffix, String routeType, String eventKind, boolean enabled) {
        CommHubTopicRoute route = new CommHubTopicRoute();
        route.setTopicSuffix(topicSuffix);
        route.setRouteType(routeType);
        route.setEventKind(eventKind);
        route.setEnabled(enabled);
        return route;
    }

    @Test
    void resolve_shouldReturnNullForUnregisteredTopic() {
        when(topicRouteMapper.selectList(any())).thenReturn(List.of());
        registry.reload();

        assertThat(registry.resolve("unknown/path")).isNull();
    }

    @Test
    void resolve_shouldReturnNullForDisabledRoute() {
        when(topicRouteMapper.selectList(any()))
                .thenReturn(List.of(route("ota/progress", "EVENT_ONLY", "OTA_PROGRESS", false)));
        registry.reload();

        assertThat(registry.resolve("ota/progress")).isNull();
    }

    @Test
    void resolve_shouldReturnRouteForEnabledTopic() {
        when(topicRouteMapper.selectList(any()))
                .thenReturn(List.of(route("ota/progress", "EVENT_ONLY", "OTA_PROGRESS", true)));
        registry.reload();

        CommHubTopicRoute resolved = registry.resolve("ota/progress");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getRouteType()).isEqualTo("EVENT_ONLY");
        assertThat(resolved.getEventKind()).isEqualTo("OTA_PROGRESS");
    }

    @Test
    void upsert_shouldRejectUnknownRouteType() {
        CommHubTopicRoute route = route("custom/path", "SCRIPT_ENGINE", null, true);

        assertThatThrownBy(() -> registry.upsert(route, "tester"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("未知 routeType");
    }

    @Test
    void upsert_shouldRequireEventKindForEventOnly() {
        CommHubTopicRoute route = route("custom/path", "EVENT_ONLY", null, true);

        assertThatThrownBy(() -> registry.upsert(route, "tester"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("必须指定 eventKind");
    }

    @Test
    void upsert_shouldRejectInvalidEventKindName() {
        CommHubTopicRoute route = route("custom/path", "EVENT_ONLY", "NOT_A_REAL_EVENT_KIND", true);

        assertThatThrownBy(() -> registry.upsert(route, "tester"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("不是合法的 EventKind");
    }

    @Test
    void upsert_shouldClearEventKindForRouteTypesNotRequiringIt() {
        CommHubTopicRoute route = route("bridge-ack", "BRIDGE_ACK", "OTA_PROGRESS", true);
        when(topicRouteMapper.selectById("bridge-ack")).thenReturn(null);
        when(topicRouteMapper.selectList(any())).thenReturn(List.of());

        registry.upsert(route, "tester");

        assertThat(route.getEventKind()).isNull();
    }

    @Test
    void upsert_shouldInsertWhenNewAndReloadCacheImmediately() {
        CommHubTopicRoute newRoute = route("custom/path", "EVENT_ONLY", "HEARTBEAT", true);
        when(topicRouteMapper.selectById("custom/path")).thenReturn(null);
        when(topicRouteMapper.selectList(any())).thenReturn(List.of(newRoute));

        registry.upsert(newRoute, "tester");

        verify(topicRouteMapper).insert(newRoute);
        assertThat(registry.resolve("custom/path")).isNotNull();
    }

    @Test
    void upsert_shouldUpdateWhenTopicSuffixAlreadyExists() {
        CommHubTopicRoute existing = route("ota/progress", "EVENT_ONLY", "OTA_PROGRESS", true);
        CommHubTopicRoute edited = route("ota/progress", "EVENT_ONLY", "OTA_STATUS_REPORT", true);
        when(topicRouteMapper.selectById("ota/progress")).thenReturn(existing);
        when(topicRouteMapper.selectList(any())).thenReturn(List.of(edited));

        registry.upsert(edited, "tester");

        verify(topicRouteMapper).updateById(edited);
        verify(topicRouteMapper, times(0)).insert(any());
    }

    @Test
    void delete_shouldRemoveAndReloadCache() {
        when(topicRouteMapper.selectList(any())).thenReturn(List.of());

        registry.delete("ota/progress");

        verify(topicRouteMapper).deleteById("ota/progress");
        assertThat(registry.resolve("ota/progress")).isNull();
    }
}
