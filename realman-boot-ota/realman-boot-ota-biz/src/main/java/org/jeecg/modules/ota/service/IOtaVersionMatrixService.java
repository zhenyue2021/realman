package org.jeecg.modules.ota.service;

import org.jeecg.modules.ota.vo.VersionMatrixQuery;
import org.jeecg.modules.ota.vo.VersionMatrixResult;

/** 版本矩阵，对齐 OTA 平台详细设计十二章（PRD 4.3、9.4.4）。 */
public interface IOtaVersionMatrixService {

    VersionMatrixResult getMatrix(VersionMatrixQuery query);
}
