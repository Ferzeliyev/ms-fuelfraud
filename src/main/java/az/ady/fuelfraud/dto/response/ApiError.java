package az.ady.fuelfraud.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Standard error payload returned by the global exception handler.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details
) {

    public static ApiError of(int status, String error, String message, String path, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, path, details);
    }
}
