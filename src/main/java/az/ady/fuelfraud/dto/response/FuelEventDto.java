package az.ady.fuelfraud.dto.response;

import az.ady.fuelfraud.enums.FuelEventType;

/**
 * API representation of a detected fuel event. Row positions are 1-based Excel
 * row numbers in the source worksheet.
 */
public record FuelEventDto(
        String sheetName,
        FuelEventType eventType,
        int startRow,
        int endRow,
        double startFuel,
        double endFuel,
        double fuelDifference,
        double threshold,
        double confidenceScore
) {
}
