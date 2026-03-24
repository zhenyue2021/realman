package org.jeecg.modules.device.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class WorkOrderExcelExportUtil {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WorkOrderExcelExportUtil() {
    }

    public static byte[] exportComplianceConfigs(List<WorkOrderComplianceConfig> list) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("工单合规配置");
            CellStyle headerStyle = headerStyle(wb);
            String[] headers = {
                    "规则ID",
                    "代理商ID", "代理商名称",
                    "企业ID", "企业名称",
                    "任务场景",
                    "是否自动预警", "自动预警配置时间(H:M:S)",
                    "是否有任务时限",
                    "工单是否需要验收",
                    "是否启用超时提交",
                    "超时提交原因(枚举)", "超时提交描述",
                    "超时未提交策略", "超时未提交策略时长(H:M:S)",
                    "应用状态",
                    "创建时间", "更新时间"
            };
            writeHeader(sheet, headers, headerStyle);

            int rowNum = 1;
            for (WorkOrderComplianceConfig c : list) {
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, c.getId());
                setCell(row, 1, c.getAgentId());
                setCell(row, 2, c.getAgentName());
                setCell(row, 3, c.getEnterpriseId());
                setCell(row, 4, c.getEnterpriseName());
                setCell(row, 5, c.getTaskScene());
                setCell(row, 6, flagStr(c.getTimeoutAlertEnabled()));
                setCell(row, 7, c.getTimeoutAlertOffset());
                setCell(row, 8, flagStr(c.getTaskLimitEnabled()));
                setCell(row, 9, flagStr(c.getAcceptanceEnabled()));
                setCell(row, 10, flagStr(c.getOvertimeEnabled()));
                setCell(row, 11, c.getOvertimeReasonEnum());
                setCell(row, 12, c.getOvertimeReasonDesc());
                setCell(row, 13, flagStr(c.getAutoCloseEnabled()));
                setCell(row, 14, c.getAutoCloseOffset());
                setCell(row, 15, c.getApplyStatus() != null && c.getApplyStatus() == 1 ? "已应用" : "未应用");
                setCell(row, 16, format(c.getCreateTime()));
                setCell(row, 17, format(c.getUpdateTime()));
            }
            autoSizeColumns(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出工单合规配置失败", e);
        }
    }

    public static byte[] exportWorkOrders(List<WorkOrder> list) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("工单列表");
            CellStyle headerStyle = headerStyle(wb);
            String[] headers = {
                    "工单ID", "工单任务名称", "代理商", "部门", "合规配置ID",
                    "币种", "单价", "总价",
                    "计划开始时间", "计划结束时间", "状态", "审核结果",
                    "操作员", "操作员电话",
                    "实际开始时间", "提交时间",
                    "超时原因来源", "超时原因",
                    "审核人", "审核时间", "审核备注",
                    "关闭人", "关闭时间", "关闭原因",
                    "创建人", "创建时间"
            };
            writeHeader(sheet, headers, headerStyle);

            int rowNum = 1;
            for (WorkOrder o : list) {
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, o.getId());
                setCell(row, 1, o.getTaskName());
                setCell(row, 2, o.getAgentName());
                setCell(row, 3, o.getDepartmentName());
                setCell(row, 4, o.getComplianceId());
                setCell(row, 5, o.getCurrency());
                setCell(row, 6, o.getUnitPrice() != null ? o.getUnitPrice().toPlainString() : null);
                setCell(row, 7, o.getTotalPrice() != null ? o.getTotalPrice().toPlainString() : null);
                setCell(row, 8, format(o.getPlanStartTime()));
                setCell(row, 9, format(o.getPlanEndTime()));
                setCell(row, 10, o.getStatus());
                setCell(row, 11, o.getAuditResult());
                setCell(row, 12, o.getOperatorName());
                setCell(row, 13, o.getOperatorPhone());
                setCell(row, 14, format(o.getActualStartTime()));
                setCell(row, 15, format(o.getSubmitTime()));
                setCell(row, 16, o.getTimeoutReasonSource());
                setCell(row, 17, o.getTimeoutReason());
                setCell(row, 18, o.getAuditBy());
                setCell(row, 19, format(o.getAuditTime()));
                setCell(row, 20, o.getAuditComment());
                setCell(row, 21, o.getCloseBy());
                setCell(row, 22, format(o.getCloseTime()));
                setCell(row, 23, o.getCloseReason());
                setCell(row, 24, o.getCreateBy());
                setCell(row, 25, format(o.getCreateTime()));
            }
            autoSizeColumns(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出工单列表失败", e);
        }
    }

    private static void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(style);
        }
    }

    private static void setCell(Row row, int col, String value) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static String format(LocalDateTime t) {
        return t != null ? t.format(DT) : "";
    }

    private static String flagStr(Integer v) {
        if (v == null) return "";
        return v == 1 ? "是" : "否";
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        // SXSSF 需要先显式开启列跟踪，否则 autoSizeColumn 会抛 IllegalStateException
        if (sheet instanceof SXSSFSheet sxssfSheet) {
            sxssfSheet.trackAllColumnsForAutoSizing();
        }
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}

