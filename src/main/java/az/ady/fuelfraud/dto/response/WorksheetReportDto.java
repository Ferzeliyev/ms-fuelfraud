package az.ady.fuelfraud.dto.response;

import java.util.List;

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
