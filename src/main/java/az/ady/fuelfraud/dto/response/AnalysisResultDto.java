package az.ady.fuelfraud.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Full report returned to the client after an Excel file has been analysed:
 * batch-level totals plus one report per worksheet.
 */
public record AnalysisResultDto(
        String fileName,
        int sheetCount,
        int totalMeasurements,
        int refuelCount,
        int theftCount,
        double totalRefueledLiters,
        double totalStolenLiters,
        Instant analyzedAt,
        List<WorksheetReportDto> reports
) {
}
