package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;

public interface IIotSlamMapService extends IService<IotSlamMap> {

    /**
     * 异步处理 GetCurrentMap 终态响应：解析 base64、上传 MinIO、逻辑删除旧记录、写入新记录。
     *
     * @param record 已完成的 SLAM 指令记录（functionName=GetCurrentMap，status=COMPLETED）
     */
    void processGetCurrentMap(IotSlamCommandRecord record);

    /**
     * 查询指定机器人的当前有效地图。
     * 若预签名 URL 已过期（剩余有效期 < 1 小时），自动刷新后返回。
     *
     * @param robotCode 机器人设备编码
     * @return 当前地图记录，无记录时返回 null
     */
    IotSlamMap getCurrentMap(String robotCode);
}
