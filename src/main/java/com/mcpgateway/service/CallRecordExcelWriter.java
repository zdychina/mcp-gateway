package com.mcpgateway.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 把调用记录写成 .xlsx（列表导出）。
 *
 * <p>两个刻意的选择：
 *
 * <ol>
 *   <li><b>SXSSF 流式写</b>：内存里只保留 {@value #ROW_WINDOW} 行，其余落到临时文件。
 *       导出上限 5000 行、每行还可能带四个抽取值，全量堆在内存里没有必要。
 *       代价是必须 dispose 掉临时文件，见 {@link #write}。</li>
 *   <li><b>文本一律走 setCellValue(String)</b>：绝不调 setCellFormula。导出内容里有
 *       Agent 传来的参数和下游返回的正文，以 {@code =} {@code +} {@code -} {@code @}
 *       开头的单元格在 CSV 里会被 Excel 当公式执行 —— 这正是不选 CSV 的原因之一。
 *       xlsx 的公式是单独的单元格类型，只要不主动写就不存在这个问题。</li>
 * </ol>
 *
 * <p>时间写成真正的日期单元格（Excel 里能排序、能筛选），时区取服务端默认时区，
 * 并写进表头 —— 页面上显示的是浏览器本地时间，两者可能不一致，不说清楚会看错。
 */
@Component
public class CallRecordExcelWriter {

    /** 内存里保留的行数，其余由 SXSSF 落到临时文件。 */
    private static final int ROW_WINDOW = 200;

    private static final String SHEET_NAME = "调用记录";

    private static final String DATE_FORMAT = "yyyy-mm-dd hh:mm:ss";

    /** 一列的宽度，单位是"字符数"，最终换算成 POI 的 1/256 字符。 */
    public record Column(String label, int widthChars) {
    }

    /** 一行的取值回调。cells 支持 String、Number、Instant 和 null（空单元格）。 */
    @FunctionalInterface
    public interface RowSink {

        void row(List<Object> cells);
    }

    /** 由调用方把行喂进来 —— 它负责分批查库，这个类只管写。 */
    @FunctionalInterface
    public interface RowFeeder {

        void feed(RowSink sink);
    }

    /**
     * 写一个工作簿到输出流。
     *
     * @param columns 表头
     * @param feeder  行来源，按需分批喂
     * @param out     输出流，由调用方关闭
     */
    public void write(List<Column> columns, RowFeeder feeder, OutputStream out) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        try {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);

            writeHeader(sheet, columns, headerStyle);

            int[] rowNumber = { 1 };
            feeder.feed(cells -> writeRow(sheet, rowNumber[0]++, cells, dateStyle));

            // 冻结表头 + 自动筛选：导出来第一件事多半就是按状态或工具名筛一下
            sheet.createFreezePane(0, 1);
            if (rowNumber[0] > 1) {
                sheet.setAutoFilter(new CellRangeAddress(0, rowNumber[0] - 1, 0, columns.size() - 1));
            }

            workbook.write(out);
        }
        finally {
            // 必须删掉 SXSSF 的临时文件，否则导出几次就在磁盘上留下几份
            workbook.dispose();
            workbook.close();
        }
    }

    private static void writeHeader(Sheet sheet, List<Column> columns, CellStyle style) {
        Row header = sheet.createRow(0);
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            Cell cell = header.createCell(index);
            cell.setCellValue(column.label());
            cell.setCellStyle(style);
            sheet.setColumnWidth(index, Math.min(column.widthChars(), 120) * 256);
        }
    }

    private static void writeRow(Sheet sheet, int rowNumber, List<Object> cells, CellStyle dateStyle) {
        Row row = sheet.createRow(rowNumber);
        for (int index = 0; index < cells.size(); index++) {
            Object value = cells.get(index);
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(index);
            if (value instanceof Instant instant) {
                cell.setCellValue(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
                cell.setCellStyle(dateStyle);
            }
            else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            }
            else {
                cell.setCellValue(value.toString());
            }
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle dateStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(DATE_FORMAT));
        return style;
    }

    /** 供调用方在 lambda 里抛 IO 异常时统一转成非受检异常。 */
    public static UncheckedIOException wrap(IOException cause) {
        return new UncheckedIOException(cause);
    }
}
