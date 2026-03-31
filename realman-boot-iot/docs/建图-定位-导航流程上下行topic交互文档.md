# 建图、定位、导航流程数据说明

## 与后端交互接口 

### 下行 TOPIC :
device/+/slam/request

### 上行

device/+/slam/states
device/+/slam/ack

### 消息说明
device/+/slam/states：上报slam地图状态及点位信息（IdleMode模式下只有状态，没有点位，MappingAndLocalization和LocalizationAndNavigation模式下既有状态又有点位 ）
上送报文示例：
```json
{ 
	"slam_nav_mode": "MappingAndLocalization", 
    "current_pose": {
      "pixel_x": 2, /* 像素位置 */
      "pixel_y": 3, /* 像素位置 */
      "yaw": 1.57
    }
}
```
device/+/slam/request：向设备发送地图请求
请求示例：
```json
{ 
    "commandId": "req_084ecb37bbd8"
	"function": "GetCurrentMap", 
    "params": {
      "target_mode": "MappingAndLocalization"
    }
}
```
device/+/slam/ack：设备响应给我的请求
响应示例：
```json
{
	"commandId": "req_084ecb37bbd8"
	"function": "功能名",
	"success": true,
	"code": 0,
	"message": "ok",
	"sequence": 1,	/* 目前响应的是第几次，示例为第一次响应 */
	"total": 2,		/* 总共需要响应几次才能完成当前请求，当前示例表示需要执行两次，这是第一次响应 */
	"data": {}
}

{
	"commandId": "req_084ecb37bbd8"
	"function": "功能名",
	"success": true,
	"code": 0,
	"message": "ok",
	"sequence": 2,	/* 目前响应的是第几次，示例为第二次响应，也是最终响应 */
	"total": 2,		/* 总共需要响应几次才能完成当前请求，当前示例表示需要执行两次，这是第二次响应，后续不会再对该请求响应数据 */
	"data": {}
}
```

## SLAM地图模式枚举

```json
IdleMode                    // 空闲模式-什么都不能做，只有状态
MappingAndLocalization      // 建图定位模式
LocalizationAndNavigation   // 定位导航模式
```

## function 功能代码
```json
SwitchMode                 // 切换模式--上方三种模式状态
GetCurrentMap              // 获取当前地图（返回地图文件或栅格地图）
SaveMap                    // 保存地图（MappingAndLocalization模式下）
SinglePointNavigation      // 单点导航（需要上送目标点位及像素）
MultiWaypointNavigation    // 多点顺序导航（逐点执行，非连续曲线跟踪）
SetInitialPose             // 重定位（设置初始位姿，用于定位恢复-页面手动触发，需要上送目标点位及像素）
```
