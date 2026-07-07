package az.ady.fuelfraud.dto.internal;

import az.ady.fuelfraud.enums.FuelEventType;

/**
 * Result of the detection engine for a single fuel event.
 * Positions are zero-based indexes into the parsed measurement series; the analysis
 * service resolves them to 1-based Excel row numbers when building the API response.
 *
 * @param sheetName       worksheet the event was detected in
 * @param type            event classification
 * @param startIndex      series index where the level change began
 * @param endIndex        series index where the level change ended
 * @param startFuel       fuel level at the start (litres)
 * @param endFuel         fuel level at the end (litres)
 * @param fuelDifference  signed level change: {@code endFuel - startFuel} (litres)
 * @param threshold       adaptive threshold (litres) in force for this worksheet
 * @param confidenceScore detection confidence in {@code [0, 1]}
 */
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

    /** Absolute magnitude of the change (litres); always positive. */
    public double volumeLiters() {
        return Math.abs(fuelDifference);
    }
}
