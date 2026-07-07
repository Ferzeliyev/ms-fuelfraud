package az.ady.fuelfraud.service;

import az.ady.fuelfraud.dto.response.AnalysisResultDto;
import org.springframework.web.multipart.MultipartFile;

public interface FuelAnalysisService {
    AnalysisResultDto analyze(MultipartFile file);
    byte[] analyzeToExcel(MultipartFile file);
}
