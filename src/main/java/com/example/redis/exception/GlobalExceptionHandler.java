package com.example.redis.exception;

import com.example.redis.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * <b>Global Exception Handler</b>
 *
 * <p>Centralized {@link RestControllerAdvice} that intercepts all exceptions thrown by REST controllers and
 * translates them into uniform {@link ApiResponse} error payloads with appropriate HTTP status codes.
 *
 * <pre>
 *  ┌────────────┐    ┌───────────────────────────┐    ┌──────────────┐
 *  │ Controller │──X─│ GlobalExceptionHandler     │───>│ ApiResponse  │
 *  │  throws    │    │ catches + maps to HTTP code│    │ error JSON   │
 *  └────────────┘    └───────────────────────────┘    └──────────────┘
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles {@link MemberNotFoundException} -- returns {@code 404 Not Found}.
     *
     * @param ex the exception containing the member and board details
     * @return error response with the not-found message
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleMemberNotFound(MemberNotFoundException ex) {
        log.warn("Member not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles Bean Validation failures -- returns {@code 400 Bad Request}
     * with a comma-separated list of field-level errors.
     *
     * @param ex the validation exception from {@code @Valid} processing
     * @return error response enumerating all field violations
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(errors));
    }

    /**
     * Handles malformed JSON request bodies -- returns {@code 400 Bad Request}.
     *
     * @param ex the deserialization exception
     * @return error response indicating invalid body format
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Invalid request body format"));
    }

    /**
     * Handles unsupported HTTP methods -- returns {@code 405 Method Not Allowed}.
     *
     * @param ex the exception identifying the unsupported method
     * @return error response naming the rejected HTTP method
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("HTTP method '%s' is not supported for this endpoint".formatted(ex.getMethod())));
    }

    /**
     * Handles missing required query/path parameters -- returns {@code 400 Bad Request}.
     *
     * @param ex the exception identifying the missing parameter
     * @return error response naming the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing parameter: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Required parameter '%s' is missing".formatted(ex.getParameterName())));
    }

    /**
     * Handles type conversion failures on parameters -- returns {@code 400 Bad Request}.
     *
     * @param ex the exception identifying the mismatched parameter type
     * @return error response naming the parameter and expected type
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Parameter '%s' must be of type %s".formatted(
                        ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown")));
    }

    /**
     * Catch-all handler for any unhandled exception -- returns {@code 500 Internal Server Error}.
     *
     * @param ex the unexpected exception
     * @return generic error response (details logged server-side)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
