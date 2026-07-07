package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.dto.internal.DetectedEvent;
import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.dto.internal.MeasurementSeries;
import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.exception.ExcelExportException;
import az.ady.fuelfraud.service.ExcelExportService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.List;

@Service
public class ExcelExportServiceImpl implements ExcelExportService {

    private static final int ROW_WINDOW = 256;

    private static final String LABEL_REFUEL = "Əlavə edilib";
    private static final String LABEL_THEFT = "Oğurlanıb";
    private static final int THRESHOLD_CELL_COLUMN = 6;
    private static final String[] SHEET_HEADERS = {"Sətir", "Yanacaq (L)", "Fərq (L)", "Hadisə"};
    private static final String[] SUMMARY_HEADERS = {
            "Vərəq", "Başlanğıc sətir", "Son sətir", "Əvvəlki həcm (L)",
            "Sonrakı həcm (L)", "Hadisə", "Dəyişən həcm (L)"};

    @Override
    public byte[] export(List<MeasurementSeries> sheets, List<DetectionResult> results) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);

            Sheet summarySheet = workbook.createSheet(uniqueSheetName(workbook, "Summary"));

            for (int s = 0; s < results.size(); s++) {
                writeWorksheet(workbook, styles, results.get(s), sheets.get(s));
            }
            fillSummary(summarySheet, styles, sheets, results);


            workbook.setForceFormulaRecalculation(true);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ExcelExportException("Failed to generate the analysed workbook", ex);
        } finally {
            workbook.dispose();
        }
    }

    private void writeWorksheet(Workbook workbook, Styles styles, DetectionResult result,
                                MeasurementSeries series) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, result.sheetName()));
        sheet.createFreezePane(0, 1);
        for (int c = 0; c < SHEET_HEADERS.length; c++) {
            sheet.setColumnWidth(c, 14 * 256);
        }
        sheet.setColumnWidth(THRESHOLD_CELL_COLUMN - 1, 12 * 256);
        sheet.setColumnWidth(THRESHOLD_CELL_COLUMN, 12 * 256);

        Row header = sheet.createRow(0);
        for (int c = 0; c < SHEET_HEADERS.length; c++) {
            textCell(header, c, SHEET_HEADERS[c], styles.header);
        }
        textCell(header, THRESHOLD_CELL_COLUMN - 1, "Hədd (L)", styles.header);
        numberCell(header, THRESHOLD_CELL_COLUMN, result.thresholdLiters(), styles.number);

        int[] rowNumbers = series.rowNumbers();
        double[] levels = series.levels();

        for (int i = 0; i < levels.length; i++) {
            int excelRow = i + 2; // 1-based position of this data row in the sheet
            Row row = sheet.createRow(i + 1);
            numberCell(row, 0, rowNumbers[i], styles.integer);
            numberCell(row, 1, levels[i], styles.number);
            if (i > 0) {
                formulaCell(row, 2, "B%d-B%d".formatted(excelRow, excelRow - 1), styles.number);
                // A threshold of 0 means "no noise/event separation found" — classify
                // nothing rather than labelling every nonzero delta as an event.
                formulaCell(row, 3,
                        "IF($G$1<=0,\"\",IF(C%1$d>$G$1,\"%2$s\",IF(C%1$d<(-$G$1),\"%3$s\",\"\")))"
                                .formatted(excelRow, LABEL_REFUEL, LABEL_THEFT),
                        styles.text);
            } else {
                blankCell(row, 2, styles.number);
                blankCell(row, 3, styles.text);
            }
        }

        applyEventHighlighting(sheet, levels.length);
    }
    private void applyEventHighlighting(Sheet sheet, int rowCount) {
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();

        ConditionalFormattingRule refuel = formatting.createConditionalFormattingRule("AND($G$1>0,$C2>$G$1)");
        PatternFormatting refuelFill = refuel.createPatternFormatting();
        refuelFill.setFillBackgroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        refuelFill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

        ConditionalFormattingRule theft = formatting.createConditionalFormattingRule("AND($G$1>0,$C2<(-$G$1))");
        PatternFormatting theftFill = theft.createPatternFormatting();
        theftFill.setFillBackgroundColor(IndexedColors.ROSE.getIndex());
        theftFill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

        CellRangeAddress[] dataRows = {CellRangeAddress.valueOf("A2:D" + (rowCount + 1))};
        formatting.addConditionalFormatting(dataRows, refuel, theft);
    }

    private void fillSummary(Sheet sheet, Styles styles, List<MeasurementSeries> sheets,
                             List<DetectionResult> results) {
        sheet.createFreezePane(0, 1);
        for (int c = 0; c < SUMMARY_HEADERS.length; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
        Row header = sheet.createRow(0);
        for (int c = 0; c < SUMMARY_HEADERS.length; c++) {
            textCell(header, c, SUMMARY_HEADERS[c], styles.header);
        }

        int rowIndex = 1;
        for (int s = 0; s < results.size(); s++) {
            DetectionResult result = results.get(s);
            MeasurementSeries series = sheets.get(s);
            for (DetectedEvent event : result.events()) {
                boolean refuel = event.type() == FuelEventType.REFUEL;
                Row row = sheet.createRow(rowIndex++);
                textCell(row, 0, result.sheetName(), styles.text(refuel));
                numberCell(row, 1, series.rowNumberAt(event.startIndex()), styles.integer(refuel));
                numberCell(row, 2, series.rowNumberAt(event.endIndex()), styles.integer(refuel));
                numberCell(row, 3, event.startFuel(), styles.number(refuel));
                numberCell(row, 4, event.endFuel(), styles.number(refuel));
                textCell(row, 5, refuel ? LABEL_REFUEL : LABEL_THEFT, styles.text(refuel));
                numberCell(row, 6, event.volumeLiters(), styles.number(refuel));
            }
        }
    }

    private void numberCell(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellValue(value);
    }

    private void formulaCell(Row row, int column, String formula, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellFormula(formula);
    }

    private void blankCell(Row row, int column, CellStyle style) {
        row.createCell(column).setCellStyle(style);
    }

    private void textCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellValue(value);
    }

    private String uniqueSheetName(Workbook workbook, String baseName) {
        String name = baseName;
        int suffix = 1;
        while (workbook.getSheet(name) != null) {
            name = "%s (%d)".formatted(baseName, suffix++);
        }
        return name;
    }

    private static final class Styles {

        private final CellStyle header;
        private final CellStyle number;
        private final CellStyle integer;
        private final CellStyle text;
        private final CellStyle refuelNumber;
        private final CellStyle refuelInteger;
        private final CellStyle refuelText;
        private final CellStyle theftNumber;
        private final CellStyle theftInteger;
        private final CellStyle theftText;

        Styles(Workbook workbook) {
            short numberFormat = workbook.createDataFormat().getFormat("0.00");

            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);

            number = workbook.createCellStyle();
            number.setDataFormat(numberFormat);
            integer = workbook.createCellStyle();
            text = workbook.createCellStyle();

            refuelNumber = filled(workbook, numberFormat, IndexedColors.LIGHT_GREEN, true);
            refuelInteger = filled(workbook, numberFormat, IndexedColors.LIGHT_GREEN, false);
            refuelText = filled(workbook, (short) 0, IndexedColors.LIGHT_GREEN, false);
            theftNumber = filled(workbook, numberFormat, IndexedColors.ROSE, true);
            theftInteger = filled(workbook, numberFormat, IndexedColors.ROSE, false);
            theftText = filled(workbook, (short) 0, IndexedColors.ROSE, false);
        }

        private static CellStyle filled(Workbook workbook, short numberFormat,
                                        IndexedColors color, boolean numeric) {
            CellStyle style = workbook.createCellStyle();
            if (numeric) {
                style.setDataFormat(numberFormat);
            }
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }

        CellStyle number(boolean refuel) {
            return refuel ? refuelNumber : theftNumber;
        }

        CellStyle integer(boolean refuel) {
            return refuel ? refuelInteger : theftInteger;
        }

        CellStyle text(boolean refuel) {
            return refuel ? refuelText : theftText;
        }
    }
}
