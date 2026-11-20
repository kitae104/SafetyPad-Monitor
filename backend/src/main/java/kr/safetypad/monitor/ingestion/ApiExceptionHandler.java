package kr.safetypad.monitor.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.safetypad.monitor.security.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed or missing JSON body");
    }

    /** DB trouble is transient from the device's point of view: it must retry, so answer 503. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> db(DataAccessException e) {
        log.error("Database failure during request: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
                .body(payload("SERVICE_UNAVAILABLE", "Temporarily unavailable, retry safely"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        // Framework 4xx (415 wrong content type, 405, 404, missing/invalid params) must not be reported as 500.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return body(status, status.name(), status.getReasonPhrase());
        }
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid request parameter");
        }
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(payload(code, message));
    }

    private Map<String, Object> payload(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", code);
        m.put("message", message);
        m.put("request_id", MDC.get(RequestIdFilter.MDC_KEY));
        return m;
    }
}
