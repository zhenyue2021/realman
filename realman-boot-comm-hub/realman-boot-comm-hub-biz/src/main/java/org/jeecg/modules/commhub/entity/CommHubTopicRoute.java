package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备端向 MQTT Topic 路由注册表，对齐设备通信中台详细设计 2.4 与已知限制第 6 项
 * （原 {@code MqttMessageDispatcher} 内硬编码 switch，现落库可配置）。
 *
 * <p>可配置的只是"Topic 后缀 -&gt; 处理类别（routeType）+ 事件种类（eventKind）"的映射；
 * 各 routeType 对应的实际处理逻辑（{@code MqttMessageDispatcher} 里的方法）仍是固定
 * Java 代码，不做成脚本/规则引擎——这是本轮明确的能力边界，不假装做到了更多。
 */
@Data
@TableName("comm_hub_topic_route")
public class CommHubTopicRoute implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("topic_suffix")
    private String topicSuffix;

    /** EXACT / PREFIX / ANT / REGEX，默认 EXACT；topicSuffix 在新模型下作为匹配 pattern 使用。 */
    @TableField("match_type")
    private String matchType;

    /** 多条规则命中时按 priority 降序选择。 */
    @TableField("priority")
    private Integer priority;

    /** 处理器键，默认等于 routeType；为后续 handler 注册机制预留。 */
    @TableField("handler_key")
    private String handlerKey;

    /** SSOT_ONLY / SSOT_AND_EVENT / EVENT_ONLY / TOKEN_REFRESH / BRIDGE_ACK / IGNORE */
    @TableField("route_type")
    private String routeType;

    /** SSOT_AND_EVENT / EVENT_ONLY 必填，对应 EventKind 枚举名；其余 routeType 为空 */
    @TableField("event_kind")
    private String eventKind;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("description")
    private String description;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
