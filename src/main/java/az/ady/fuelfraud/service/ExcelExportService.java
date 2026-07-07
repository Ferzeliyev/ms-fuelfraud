package az.ady.fuelfraud.service;

import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.dto.internal.MeasurementSeries;

import java.util.List;

public interface ExcelExportService {
    byte[] export(List<MeasurementSeries> sheets, List<DetectionResult> results);
}
