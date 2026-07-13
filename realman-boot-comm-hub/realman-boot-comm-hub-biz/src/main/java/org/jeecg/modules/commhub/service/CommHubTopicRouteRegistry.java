package org.jeecg.modules.commhub.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.contract.event.EventKind;
import org.jeecg.modules.commhub.entity.CommHubTopicRoute;
import org.jeecg.modules.commhub.mapper.CommHubTopicRouteMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Topic 路由注册表的内存缓存，落库到 {@code comm_hub_topic_route}（设备通信中台
 * 详细设计 2.4、已知限制第 6 项）。启动时加载一次，之后定时刷新，管理端增删改
 * 路由后也可调用 {@link #reload()} 立即生效，不强制等下一轮定时刷新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommHubTopicRouteRegistry {

    public static final String MATCH_TYPE_EXACT = "EXACT";
    public static final String MATCH_TYPE_PREFIX = "PREFIX";
    public static final String MATCH_TYPE_ANT_PATTERN = "ANT_PATTERN";
    public static final String MATCH_TYPE_REGEX = "REGEX";

    private static final Set<String> ROUTE_TYPES_REQUIRING_EVENT_KIND = Set.of("SSOT_AND_EVENT", "EVENT_ONLY");
    private static final Set<String> VALID_ROUTE_TYPES = Set.of(
            "SSOT_ONLY", "SSOT_AND_EVENT", "EVENT_ONLY", "TOKEN_REFRESH", "BRIDGE_ACK", "IGNORE");
    private static final Set<String> VALID_MATCH_TYPES = Set.of(
            MATCH_TYPE_EXACT, MATCH_TYPE_PREFIX, MATCH_TYPE_ANT_PATTERN, MATCH_TYPE_REGEX);
    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();
    private static final Comparator<CommHubTopicRoute> ROUTE_ORDER = Comparator
            .comparing((CommHubTopicRoute r) -> priorityOf(r), Comparator.reverseOrder())
            .thenComparing(r -> matchTypeRank(normalizeMatchType(r.getMatchType())))
            .thenComparing((CommHubTopicRoute r) -> lengthOf(r.getTopicSuffix()), Comparator.reverseOrder())
            .thenComparing(CommHubTopicRoute::getTopicSuffix, Comparator.nullsLast(String::compareTo));

    private final CommHubTopicRouteMapper topicRouteMapper;

    /** 按优先级预排序后的快照；resolve 只读遍历，避免消息主链路做排序或查库。 */
    private volatile List<CommHubTopicRoute> cache = List.of();

    @PostConstruct
    public void init() {
        reload();
    }

    @Scheduled(fixedDelayString = "${comm-hub.topic-route.refresh-interval-ms:60000}")
    public void reload() {
        try {
            List<CommHubTopicRoute> routes = topicRouteMapper.selectList(null);
            routes.forEach(this::normalizeDefaults);
            cache = routes.stream().sorted(ROUTE_ORDER).toList();
            log.debug("[comm-hub] Topic 路由注册表已刷新，共 {} 条", cache.size());
        } catch (Exception e) {
            log.warn("[comm-hub] Topic 路由注册表刷新失败，沿用旧缓存: {}", e.getMessage());
        }
    }

    /** 返回 null 表示该 Topic 后缀未注册（既不在库里，也不是历史硬编码遗留），调用方应忽略。 */
    public CommHubTopicRoute resolve(String topicSuffix) {
        if (!StringUtils.hasText(topicSuffix)) {
            return null;
        }
        for (CommHubTopicRoute route : cache) {
            if (Boolean.TRUE.equals(route.getEnabled()) && matches(route, topicSuffix)) {
                return route;
            }
        }
        return null;
    }

    /** 管理端查询台账，直接读缓存（与实际生效路由完全一致，无需额外查库）。 */
    public List<CommHubTopicRoute> list() {
        return List.copyOf(cache);
    }

    /**
     * 新增/编辑路由（新增与编辑合一：topicSuffix 已存在则覆盖）。校验 routeType 必须是
     * 已知类别之一（TOKEN_REFRESH/BRIDGE_ACK 对应固定处理逻辑，新增任意其它 routeType
     * 字符串不会有实际处理方法响应，故收紧为白名单校验，避免管理端录入拼写错误后
     * 静默不生效）；SSOT_AND_EVENT/EVENT_ONLY 要求 eventKind 是合法的 {@link EventKind}
     * 枚举名。写库后立即 {@link #reload()}。
     */
    public void upsert(CommHubTopicRoute route, String operator) {
        validateRoute(route);
        route.setUpdatedBy(operator);
        route.setUpdatedAt(LocalDateTime.now());
        if (route.getEnabled() == null) {
            route.setEnabled(true);
        }
        if (topicRouteMapper.selectById(route.getTopicSuffix()) == null) {
            topicRouteMapper.insert(route);
        } else {
            topicRouteMapper.updateById(route);
        }
        reload();
    }

    public void validateRoute(CommHubTopicRoute route) {
        if (route == null) {
            throw new JeecgBootBizTipException("路由不能为空");
        }
        if (!StringUtils.hasText(route.getTopicSuffix())) {
            throw new JeecgBootBizTipException("topicSuffix 不能为空");
        }
        normalizeDefaults(route);
        if (!VALID_MATCH_TYPES.contains(route.getMatchType())) {
            throw new JeecgBootBizTipException("未知 matchType：" + route.getMatchType() + "，合法值：" + VALID_MATCH_TYPES);
        }
        validatePattern(route.getMatchType(), route.getTopicSuffix());
        if (!VALID_ROUTE_TYPES.contains(route.getRouteType())) {
            throw new JeecgBootBizTipException("未知 routeType：" + route.getRouteType() + "，合法值：" + VALID_ROUTE_TYPES);
        }
        if (ROUTE_TYPES_REQUIRING_EVENT_KIND.contains(route.getRouteType())) {
            if (!StringUtils.hasText(route.getEventKind())) {
                throw new JeecgBootBizTipException("routeType=" + route.getRouteType() + " 必须指定 eventKind");
            }
            try {
                EventKind.valueOf(route.getEventKind());
            } catch (IllegalArgumentException e) {
                throw new JeecgBootBizTipException("eventKind 不是合法的 EventKind 枚举名：" + route.getEventKind());
            }
        } else {
            route.setEventKind(null);
        }
    }

    public void delete(String topicSuffix) {
        topicRouteMapper.deleteById(topicSuffix);
        reload();
    }

    private boolean matches(CommHubTopicRoute route, String topicSuffix) {
        return switch (normalizeMatchType(route.getMatchType())) {
            case MATCH_TYPE_PREFIX -> topicSuffix.startsWith(route.getTopicSuffix());
            case MATCH_TYPE_ANT_PATTERN -> ANT_PATH_MATCHER.match(route.getTopicSuffix(), topicSuffix);
            case MATCH_TYPE_REGEX -> Pattern.matches(route.getTopicSuffix(), topicSuffix);
            default -> topicSuffix.equals(route.getTopicSuffix());
        };
    }

    private void validatePattern(String matchType, String topicSuffix) {
        if (MATCH_TYPE_REGEX.equals(matchType)) {
            try {
                Pattern.compile(topicSuffix);
            } catch (PatternSyntaxException e) {
                throw new JeecgBootBizTipException("topicSuffix 不是合法正则表达式：" + e.getDescription());
            }
        } else if (MATCH_TYPE_ANT_PATTERN.equals(matchType)) {
            try {
                ANT_PATH_MATCHER.match(topicSuffix, topicSuffix);
            } catch (IllegalArgumentException e) {
                throw new JeecgBootBizTipException("topicSuffix 不是合法 ANT_PATTERN：" + e.getMessage());
            }
        }
    }

    private void normalizeDefaults(CommHubTopicRoute route) {
        route.setMatchType(normalizeMatchType(route.getMatchType()));
        if (route.getPriority() == null) {
            route.setPriority(0);
        }
    }

    private static String normalizeMatchType(String matchType) {
        return StringUtils.hasText(matchType) ? matchType.trim().toUpperCase() : MATCH_TYPE_EXACT;
    }

    private static int priorityOf(CommHubTopicRoute route) {
        return route.getPriority() == null ? 0 : route.getPriority();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static int matchTypeRank(String matchType) {
        return switch (matchType) {
            case MATCH_TYPE_EXACT -> 0;
            case MATCH_TYPE_PREFIX -> 1;
            case MATCH_TYPE_ANT_PATTERN -> 2;
            case MATCH_TYPE_REGEX -> 3;
            default -> 4;
        };
    }
}
