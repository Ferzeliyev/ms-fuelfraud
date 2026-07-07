package az.ady.fuelfraud.exception;

import az.ady.fuelfraud.dto.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ApiError> handleInvalidFile(InvalidFileException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
                                                      HttpServletRequest request) {
        String message = "Required multipart field '%s' is missing — attach the Excel workbook as form-data field '%s'"
                .formatted(ex.getRequestPartName(), ex.getRequestPartName());
        return build(HttpStatus.BAD_REQUEST, message, request, List.of());
    }

    @ExceptionHandler(ExcelParsingException.class)
    public ResponseEntity<ApiError> handleParsing(ExcelParsingException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size", request, List.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed multipart request — attach the Excel workbook as form-data",
                request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> "%s: %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {

            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            String detail = errorResponse.getBody().getDetail();
            return build(status, StringUtils.hasText(detail) ? detail : status.getReasonPhrase(),
                    request, List.of());
        }
        log.error("Unhandled exception while processing {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request,
                                           List<String> details) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), details);
        return ResponseEntity.status(status).body(body);
    }
}
