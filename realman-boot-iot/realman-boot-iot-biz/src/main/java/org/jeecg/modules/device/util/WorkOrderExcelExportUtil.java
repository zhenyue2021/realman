package org.jeecg.modules.device.util;

import org.apache.poi.ss.usermodel.*;
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
                    "配置ID", "代理商", "企业", "任务名称", "任务描述", "任务类型", "任务级别",
                    "超时提醒启用", "提前秒数",
                    "提交时限启用",
                    "验收启用", "必须图片", "图片说明",
                    "超时提交启用", "超时原因最大字数",
                    "自动关闭启用", "自动关闭秒数",
                    "状态", "创建时间", "更新时间"
            };
            writeHeader(sheet, headers, headerStyle);

            int rowNum = 1;
            for (WorkOrderComplianceConfig c : list) {
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, c.getId());
                setCell(row, 1, c.getAgentName());
                setCell(row, 2, c.getEnterpriseName());
                setCell(row, 3, c.getTaskName());
                setCell(row, 4, c.getTaskDesc());
                setCell(row, 5, c.getTaskType());
                setCell(row, 6, c.getTaskLevel());
                setCell(row, 7, flagStr(c.getTimeoutAlertEnabled()));
                setCell(row, 8, intStr(c.getTimeoutAlertSeconds()));
                setCell(row, 9, flagStr(c.getSubmitLimitEnabled()));
                setCell(row, 10, flagStr(c.getAcceptanceEnabled()));
                setCell(row, 11, flagStr(c.getAcceptanceImageRequired()));
                setCell(row, 12, c.getAcceptanceImageDesc());
                setCell(row, 13, flagStr(c.getOvertimeSubmitEnabled()));
                setCell(row, 14, intStr(c.getOvertimeReasonMaxLen()));
                setCell(row, 15, flagStr(c.getAutoCloseEnabled()));
                setCell(row, 16, intStr(c.getAutoCloseSeconds()));
                setCell(row, 17, c.getStatus() != null && c.getStatus() == 1 ? "已启用" : "未启用");
                setCell(row, 18, format(c.getCreateTime()));
                setCell(row, 19, format(c.getUpdateTime()));
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
                    "工单ID", "代理商", "部门", "合规配置ID",
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
                setCell(row, 1, o.getAgentName());
                setCell(row, 2, o.getDepartmentName());
                setCell(row, 3, o.getComplianceId());
                setCell(row, 4, format(o.getPlanStartTime()));
                setCell(row, 5, format(o.getPlanEndTime()));
                setCell(row, 6, o.getStatus());
                setCell(row, 7, o.getAuditResult());
                setCell(row, 8, o.getOperatorName());
                setCell(row, 9, o.getOperatorPhone());
                setCell(row, 10, format(o.getActualStartTime()));
                setCell(row, 11, format(o.getSubmitTime()));
                setCell(row, 12, o.getTimeoutReasonSource());
                setCell(row, 13, o.getTimeoutReason());
                setCell(row, 14, o.getAuditBy());
                setCell(row, 15, format(o.getAuditTime()));
                setCell(row, 16, o.getAuditComment());
                setCell(row, 17, o.getCloseBy());
                setCell(row, 18, format(o.getCloseTime()));
                setCell(row, 19, o.getCloseReason());
                setCell(row, 20, o.getCreateBy());
                setCell(row, 21, format(o.getCreateTime()));
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

    private static String intStr(Integer v) {
        return v != null ? String.valueOf(v) : "";
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}

