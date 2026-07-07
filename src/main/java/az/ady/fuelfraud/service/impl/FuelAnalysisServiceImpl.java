package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.client.FraudAlertClient;
import az.ady.fuelfraud.dto.response.AnalysisResultDto;
import az.ady.fuelfraud.dto.internal.DetectedEvent;
import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.dto.response.FuelEventDto;
import az.ady.fuelfraud.dto.internal.MeasurementSeries;
import az.ady.fuelfraud.dto.response.WorksheetReportDto;
import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.service.ExcelExportService;
import az.ady.fuelfraud.service.ExcelParsingService;
import az.ady.fuelfraud.service.FuelAnalysisService;
import az.ady.fuelfraud.service.FuelEventDetector;
import az.ady.fuelfraud.util.FuelMathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuelAnalysisServiceImpl implements FuelAnalysisService {

    private final ExcelParsingService excelParsingService;
    private final FuelEventDetector fuelEventDetector;
    private final ExcelExportService excelExportService;
    private final FraudAlertClient fraudAlertClient;

    @Override
    public AnalysisResultDto analyze(MultipartFile file) {
        String fileName = resolveFileName(file);
        List<MeasurementSeries> sheets = excelParsingService.parse(file);
        List<DetectionResult> results = detect(sheets);

        List<WorksheetReportDto> reports = toReports(sheets, results);
        notifyTheftAlerts(results);

        AnalysisResultDto result = buildResult(fileName, results, reports);
        log.info("Analysis of '{}' complete: {} sheet(s), {} refuel(s) totalling {} L, {} theft(s) totalling {} L",
                result.fileName(), result.sheetCount(), result.refuelCount(),
                result.totalRefueledLiters(), result.theftCount(), result.totalStolenLiters());
        return result;
    }

    @Override
    public byte[] analyzeToExcel(MultipartFile file) {
        List<MeasurementSeries> sheets = excelParsingService.parse(file);
        List<DetectionResult> results = detect(sheets);
        notifyTheftAlerts(results);

        log.info("Analysed workbook generated for '{}': {} sheet(s)", resolveFileName(file), results.size());
        return excelExportService.export(sheets, results);
    }

    private List<DetectionResult> detect(List<MeasurementSeries> sheets) {
        List<DetectionResult> results = new ArrayList<>(sheets.size());
        for (MeasurementSeries series : sheets) {
            results.add(fuelEventDetector.analyze(series.sheetName(), series.levels()));
        }
        return results;
    }

    private List<WorksheetReportDto> toReports(List<MeasurementSeries> sheets,
                                               List<DetectionResult> results) {
        List<WorksheetReportDto> reports = new ArrayList<>(results.size());

        for (int s = 0; s < results.size(); s++) {
            DetectionResult result = results.get(s);
            MeasurementSeries series = sheets.get(s);
            reports.add(new WorksheetReportDto(
                    result.sheetName(),
                    result.measurementCount(),
                    result.thresholdLiters(),
                    result.noiseLevelLiters(),
                    result.noiseMovementCount(),
                    result.refuelCount(),
                    result.theftCount(),
                    result.totalRefueledLiters(),
                    result.totalStolenLiters(),
                    toEventDtos(result.events(), series)));
        }
        return reports;
    }

    private List<FuelEventDto> toEventDtos(List<DetectedEvent> detected, MeasurementSeries series) {
        List<FuelEventDto> events = new ArrayList<>(detected.size());
        for (DetectedEvent event : detected) {
            events.add(new FuelEventDto(
                    event.sheetName(),
                    event.type(),
                    series.rowNumberAt(event.startIndex()),
                    series.rowNumberAt(event.endIndex()),
                    event.startFuel(),
                    event.endFuel(),
                    event.fuelDifference(),
                    event.threshold(),
                    event.confidenceScore()));
        }
        return events;
    }

    private AnalysisResultDto buildResult(String fileName, List<DetectionResult> results,
                                          List<WorksheetReportDto> reports) {
        return new AnalysisResultDto(
                fileName,
                results.size(),
                results.stream().mapToInt(DetectionResult::measurementCount).sum(),
                results.stream().mapToInt(DetectionResult::refuelCount).sum(),
                results.stream().mapToInt(DetectionResult::theftCount).sum(),
                FuelMathUtils.round2(results.stream().mapToDouble(DetectionResult::totalRefueledLiters).sum()),
                FuelMathUtils.round2(results.stream().mapToDouble(DetectionResult::totalStolenLiters).sum()),
                Instant.now(),
                reports);
    }

    private void notifyTheftAlerts(List<DetectionResult> results) {
        results.stream()
                .flatMap(result -> result.events().stream())
                .filter(event -> event.type() == FuelEventType.THEFT)
                .forEach(fraudAlertClient::sendTheftAlert);
    }

    private String resolveFileName(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        return StringUtils.hasText(name) ? name : "unknown.xlsx";
    }
}
