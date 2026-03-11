package org.jeecg.modules.device.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 设备与授权列表导出 Excel 工具（POI）
 */
public final class DeviceExcelExportUtil {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_EXPORT = 10000;

    public static int getMaxExportRows() {
        return MAX_EXPORT;
    }

    /** 设备列表导出 */
    public static byte[] exportDevices(List<IotDevice> list) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("设备列表");
            CellStyle headerStyle = headerStyle(wb);
            CellStyle dateStyle = dateStyle(wb);

            String[] headers = {"设备ID", "设备编号", "设备名称", "设备类型", "产品ID", "型号", "序列号", "固件版本",
                    "状态", "描述", "最后上线时间", "最后下线时间", "经度", "纬度", "创建时间"};
            writeHeader(sheet, headers, headerStyle);

            int rowNum = 1;
            for (IotDevice d : list) {
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, d.getId());
                setCell(row, 1, d.getDeviceCode());
                setCell(row, 2, d.getDeviceName());
                setCell(row, 3, deviceTypeStr(d.getDeviceType()));
                setCell(row, 4, d.getProductId());
                setCell(row, 5, d.getDeviceModel());
                setCell(row, 6, d.getSerialNumber());
                setCell(row, 7, d.getFirmwareVersion());
                setCell(row, 8, statusStr(d.getStatus()));
                setCell(row, 9, d.getDescription());
                setCell(row, 10, format(d.getLastOnlineTime()));
                setCell(row, 11, format(d.getLastOfflineTime()));
                setCell(row, 12, d.getLongitude() != null ? d.getLongitude().toString() : "");
                setCell(row, 13, d.getLatitude() != null ? d.getLatitude().toString() : "");
                setCell(row, 14, format(d.getCreateTime()), dateStyle);
            }
            autoSizeColumns(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 授权列表导出 */
    public static byte[] exportAuthList(List<IotDeviceAuth> list) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("授权列表");
            CellStyle headerStyle = headerStyle(wb);
            CellStyle dateStyle = dateStyle(wb);

            String[] headers = {"授权ID", "主体类型", "主体ID", "主控端ID", "设备ID", "设备编码", "管理账号ID", "管理账号",
                    "生效时间", "失效时间", "状态", "创建时间"};
            writeHeader(sheet, headers, headerStyle);

            int rowNum = 1;
            for (IotDeviceAuth a : list) {
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, a.getId());
                setCell(row, 1, a.getSubjectType());
                setCell(row, 2, a.getSubjectId());
                setCell(row, 3, a.getControllerId());
                setCell(row, 4, a.getDeviceId());
                setCell(row, 5, a.getDeviceCode());
                setCell(row, 6, a.getAdminUserId());
                setCell(row, 7, a.getAdminUsername());
                setCell(row, 8, format(a.getEffectiveTime()), dateStyle);
                setCell(row, 9, format(a.getExpireTime()), dateStyle);
                setCell(row, 10, a.getStatus() != null && a.getStatus() == 1 ? "启用" : "禁用");
                setCell(row, 11, format(a.getCreateTime()), dateStyle);
            }
            autoSizeColumns(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
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

    private static void setCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        c.setCellStyle(style);
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static CellStyle dateStyle(Workbook wb) {
        return wb.createCellStyle();
    }

    private static String format(LocalDateTime t) {
        return t != null ? t.format(DT) : "";
    }

    private static String deviceTypeStr(Integer t) {
        if (t == null) return "";
        return t == 1 ? "机器人设备" : (t == 2 ? "主控设备" : String.valueOf(t));
    }

    private static String statusStr(Integer s) {
        if (s == null) return "";
        switch (s) {
            case 0: return "未激活";
            case 1: return "在线";
            case 2: return "离线";
            case 3: return "禁用";
            default: return String.valueOf(s);
        }
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
