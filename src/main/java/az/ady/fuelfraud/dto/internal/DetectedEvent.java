package az.ady.fuelfraud.dto.internal;

import az.ady.fuelfraud.enums.FuelEventType;

public record DetectedEvent(
        String sheetName,
        FuelEventType type,
        int startIndex,
        int endIndex,
        double startFuel,
        double endFuel,
        double fuelDifference,
        double threshold,
        double confidenceScore
) {

    public double volumeLiters() {
        return Math.abs(fuelDifference);
    }
}
