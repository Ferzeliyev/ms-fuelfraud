package az.ady.fuelfraud.service;

import az.ady.fuelfraud.dto.internal.MeasurementSeries;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ExcelParsingService {

    List<MeasurementSeries> parse(MultipartFile file);
}
