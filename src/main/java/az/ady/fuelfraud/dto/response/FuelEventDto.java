package az.ady.fuelfraud.dto.response;

import az.ady.fuelfraud.enums.FuelEventType;

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
