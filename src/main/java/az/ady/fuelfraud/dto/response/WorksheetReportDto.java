package az.ady.fuelfraud.dto.response;

import java.util.List;

/**
 * Per-worksheet analysis report: the calculated threshold, noise characteristics,
 * detected events and the stolen/refuelled totals.
 */
public record WorksheetReportDto(
        String sheetName,
        int measurementCount,
        double thresholdLiters,
        double noiseLevelLiters,
        int noiseMovementCount,
        int refuelCount,
        int theftCount,
        double totalRefueledLiters,
        double totalStolenLiters,
        List<FuelEventDto> events
) {
}
