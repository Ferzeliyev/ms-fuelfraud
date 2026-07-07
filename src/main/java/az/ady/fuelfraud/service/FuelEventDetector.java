package az.ady.fuelfraud.service;

import az.ady.fuelfraud.dto.internal.DetectionResult;

public interface FuelEventDetector {
    DetectionResult analyze(String sheetName, double[] fuelLevels);
}
