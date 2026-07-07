package az.ady.fuelfraud.controller;

import az.ady.fuelfraud.dto.response.AnalysisResultDto;
import az.ady.fuelfraud.dto.response.ApiError;
import az.ady.fuelfraud.service.FuelAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/fuel-fraud")
@RequiredArgsConstructor
@Tag(name = "Fuel Fraud", description = "Analyse fuel-sensor Excel exports")
public class FuelFraudController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final FuelAnalysisService fuelAnalysisService;

    @Operation(summary = "Analyse an Excel file",
            description = "analyses every worksheet")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed; full result returned"),
            @ApiResponse(responseCode = "400", description = "Missing/empty file or unsupported type",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "File exceeds the upload size limit",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Workbook could not be parsed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalysisResultDto analyze(
            @Parameter(description = "Excel workbook (.xlsx/.xls); one fuel-level column per worksheet")
            @RequestParam("file") MultipartFile file) {
        return fuelAnalysisService.analyze(file);
    }

    @Operation(summary = "Analyse an Excel file and download the annotated workbook",
            description = "Uploads a fuel-sensor workbook, analyses every worksheet and returns an `.xlsx`")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Annotated workbook returned"),
            @ApiResponse(responseCode = "400", description = "Missing/empty file or unsupported type",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "File exceeds the upload size limit",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Workbook could not be parsed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/analyze/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = XLSX_MEDIA_TYPE)
    public ResponseEntity<byte[]> analyzeToExcel(
            @Parameter(description = "Excel workbook (.xlsx/.xls); one fuel-level column per worksheet")
            @RequestParam("file") MultipartFile file) {
        byte[] workbook = fuelAnalysisService.analyzeToExcel(file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(analyzedFileName(file), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(workbook);
    }
    private String analyzedFileName(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        String base = StringUtils.hasText(original)
                ? StringUtils.stripFilenameExtension(StringUtils.getFilename(original))
                : "analysis";
        return base + "-analyzed.xlsx";
    }
}
