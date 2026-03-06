package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import java.util.List;
@Mapper
public interface IotOtaUpgradeRecordMapper extends BaseMapper<IotOtaUpgradeRecord> {
    int batchInsert(@Param("records") List<IotOtaUpgradeRecord> records);
}
