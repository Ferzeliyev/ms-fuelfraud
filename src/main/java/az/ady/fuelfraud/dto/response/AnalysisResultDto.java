package az.ady.fuelfraud.dto.response;

import java.time.Instant;
import java.util.List;

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
