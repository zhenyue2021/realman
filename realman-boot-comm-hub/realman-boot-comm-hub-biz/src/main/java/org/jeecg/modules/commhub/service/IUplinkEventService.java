package org.jeecg.modules.commhub.service;

import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.vo.UplinkEventDTO;
import org.jeecg.modules.commhub.vo.UplinkEventQuery;

/**
 * 统一上行事件的落库 + Webhook 转发 + 轮询兜底查询，对应设备通信中台详细设计
 * 4.3.2、五 统一上行事件模型。
 */
public interface IUplinkEventService {

    /** 落库并按租户/事件种类匹配 Webhook 订阅做 HMAC 签名推送（尽力而为，带重试）。 */
    void ingest(DeviceUplinkEvent event);

    PageResult<UplinkEventDTO> queryPage(UplinkEventQuery query);
}
