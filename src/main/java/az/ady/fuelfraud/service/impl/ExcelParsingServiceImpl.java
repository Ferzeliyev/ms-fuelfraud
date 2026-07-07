package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.dto.internal.MeasurementSeries;
import az.ady.fuelfraud.exception.ExcelParsingException;
import az.ady.fuelfraud.exception.InvalidFileException;
import az.ady.fuelfraud.service.ExcelParsingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Apache POI based Excel reader.
 *
 * <ul>
 *     <li><b>Streams xlsx</b> — worksheets are read via the SAX event API
 *     ({@link XSSFReader}), so the workbook DOM is never materialised and memory
 *     stays flat regardless of row count (multi-million-row sheets included);</li>
 *     <li><b>supports legacy xls</b> via the DOM reader (the format itself is capped
 *     at 65k rows, so streaming brings nothing there);</li>
 *     <li><b>supports multiple sheets</b> — every worksheet is read independently;</li>
 *     <li><b>reads numeric values</b> from the measurement column — always column A (numeric cells,
 *     cached numeric formula results, and numeric text with dot or comma separator);</li>
 *     <li><b>ignores empty rows</b> silently;</li>
 *     <li><b>ignores invalid cells</b> (non-numeric text, negative, NaN/Infinity) —
 *     counted and logged, never propagated;</li>
 *     <li><b>collects into primitive arrays</b> ({@link MeasurementSeries}) — no
 *     per-measurement objects are allocated.</li>
 * </ul>
 */
@Slf4j
@Service
public class ExcelParsingServiceImpl implements ExcelParsingService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xls", "xlsx");
    private static final int MIN_READINGS_PER_SHEET = 2;

    private static final DataFormatter RAW_VALUES = new DataFormatter(Locale.ROOT) {
        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                                            boolean use1904Windowing) {
            return Double.toString(value);
        }
    };

    @Override
    public List<MeasurementSeries> parse(MultipartFile file) {
        validateFile(file);
        boolean legacyXls = "xls".equalsIgnoreCase(
                StringUtils.getFilenameExtension(file.getOriginalFilename()));

        try (InputStream in = file.getInputStream()) {
            List<MeasurementSeries> sheets = legacyXls ? readLegacyXls(in) : streamXlsx(in);
            if (sheets.isEmpty()) {
                throw new ExcelParsingException("No worksheet with usable measurement data was found in the workbook");
            }
            log.info("Parsed {} worksheet(s) from '{}'", sheets.size(), file.getOriginalFilename());
            return sheets;
        } catch (ExcelParsingException ex) {
            throw ex;
        } catch (Exception ex) {
            // Covers IOException plus POI's unchecked failures (encrypted, legacy
            // BIFF5, corrupt records, misnamed formats) from both reader paths.
            throw new ExcelParsingException("Failed to read the uploaded workbook", ex);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("The uploaded file is missing or empty");
        }
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        if (extension == null || !SUPPORTED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new InvalidFileException("Unsupported file type; expected one of " + SUPPORTED_EXTENSIONS);
        }
    }

    private List<MeasurementSeries> streamXlsx(InputStream in) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(in)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();

            List<MeasurementSeries> sheets = new ArrayList<>();
            XSSFReader.SheetIterator sheetIterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheetIterator.hasNext()) {
                try (InputStream sheetStream = sheetIterator.next()) {
                    String sheetName = sheetIterator.getSheetName();
                    SheetCollector collector = new SheetCollector();

                    XMLReader xmlReader = XMLHelper.newXMLReader();
                    xmlReader.setContentHandler(new XSSFSheetXMLHandler(
                            styles, sharedStrings, collector, RAW_VALUES, false));
                    xmlReader.parse(new InputSource(sheetStream));

                    addIfUsable(sheets, sheetName, collector);
                }
            }
            return sheets;
        }
    }

    private static final class SheetCollector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final SeriesBuilder builder = new SeriesBuilder();
        private int invalidCells;
        private int currentRow;
        private boolean cellSeenInRow;

        @Override
        public void startRow(int rowNum) {
            currentRow = rowNum;
            cellSeenInRow = false;
        }

        @Override
        public void endRow(int rowNum) {
            // empty rows never report cells — ignored by design
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference != null && new CellReference(cellReference).getCol() != 0) {
                return; // the measurement column is A; stray cells elsewhere are not readings
            }
            if (cellSeenInRow) {
                return;
            }
            cellSeenInRow = true;

            double value = parseNumeric(formattedValue);
            if (isValidFuelLevel(value)) {
                builder.add(currentRow + 1, value);
            } else if (builder.size() > 0 || !Double.isNaN(value)) {
                // Tolerate a header row before any data; count everything else as invalid.
                invalidCells++;
            }
        }
    }

    private List<MeasurementSeries> readLegacyXls(InputStream in) throws IOException {
        try (Workbook workbook = new HSSFWorkbook(in)) {
            List<MeasurementSeries> sheets = new ArrayList<>();
            for (Sheet sheet : workbook) {
                SheetCollector collector = new SheetCollector();
                for (Row row : sheet) {
                    Cell cell = measurementCell(row);
                    if (cell == null) {
                        continue; // empty measurement column — ignored
                    }
                    collector.startRow(row.getRowNum());
                    collector.cell(null, readCellValue(cell), null);
                }
                addIfUsable(sheets, sheet.getSheetName(), collector);
            }
            return sheets;
        }
    }

    private Cell measurementCell(Row row) {
        if (row == null) {
            return null; // physically empty row
        }
        // The measurement column is A; stray values in other columns are not readings.
        return row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private String readCellValue(Cell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        if (type == CellType.NUMERIC) {
            return Double.toString(cell.getNumericCellValue());
        }
        if (type == CellType.STRING) {
            return cell.getStringCellValue();
        }
        return null;
    }

    private static void addIfUsable(List<MeasurementSeries> sheets, String sheetName,
                                    SheetCollector collector) {
        if (collector.invalidCells > 0) {
            log.warn("Worksheet '{}': ignored {} invalid cell(s)", sheetName, collector.invalidCells);
        }
        if (collector.builder.size() >= MIN_READINGS_PER_SHEET) {
            sheets.add(collector.builder.build(sheetName));
        } else {
            log.warn("Worksheet '{}' skipped: only {} valid reading(s)",
                    sheetName, collector.builder.size());
        }
    }

    private static double parseNumeric(String text) {
        if (text == null) {
            return Double.NaN;
        }
        String normalized = text.trim().replace(',', '.');
        if (normalized.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static boolean isValidFuelLevel(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static final class SeriesBuilder {

        private int[] rowNumbers = new int[1024];
        private double[] levels = new double[1024];
        private int size;

        void add(int rowNumber, double level) {
            if (size == levels.length) {
                rowNumbers = Arrays.copyOf(rowNumbers, size * 2);
                levels = Arrays.copyOf(levels, size * 2);
            }
            rowNumbers[size] = rowNumber;
            levels[size] = level;
            size++;
        }

        int size() {
            return size;
        }

        MeasurementSeries build(String sheetName) {
            return new MeasurementSeries(sheetName,
                    Arrays.copyOf(rowNumbers, size), Arrays.copyOf(levels, size));
        }
    }
}
