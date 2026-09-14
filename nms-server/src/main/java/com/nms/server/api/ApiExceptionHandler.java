package com.nms.server.api;

import com.nms.server.poller.CheckRequestFactory;
import com.nms.server.security.AuthenticationService;
import com.nms.server.trigger.expression.ExpressionParser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into responses an operator can act on.
 *
 * <p>Messages are written for the person reading them in the interface. "No
 * such template: 'Template: IP camera'" tells them what to fix; a stack trace
 * or a bare 500 does not.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthenticationService.AuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> onAuthenticationFailed(
            AuthenticationService.AuthenticationFailedException e, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, e.getMessage(), request);
    }

    /**
     * A denial raised by {@code @PreAuthorize} inside a controller.
     *
     * <p>Handled explicitly because it is thrown after the security filter
     * chain has already passed the request through, so the chain's access
     * denied handler never sees it. Without this it would fall through to the
     * catch-all below and be reported as a server fault -- telling an operator
     * the product is broken when in fact their role simply does not permit the
     * action.
     */
    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, Object>> onAccessDenied(
            org.springframework.security.authorization.AuthorizationDeniedException e,
            HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN,
                "Your role does not grant permission for this action", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    /**
     * A refused operation rather than a malformed one, e.g. deleting a host
     * whose items other triggers still reference. 409 rather than 400 because
     * the request was well formed; the system's current state is what forbids
     * it.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onIllegalState(
            IllegalStateException e, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(ExpressionParser.ExpressionException.class)
    public ResponseEntity<Map<String, Object>> onBadExpression(
            ExpressionParser.ExpressionException e, HttpServletRequest request) {
        // The parser's message already names the position and quotes the
        // expression, which is exactly what the author needs.
        return response(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(CheckRequestFactory.UncollectableItemException.class)
    public ResponseEntity<Map<String, Object>> onUncollectableItem(
            CheckRequestFactory.UncollectableItemException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationFailure(
            MethodArgumentNotValidException e, HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> body = body(HttpStatus.BAD_REQUEST,
                "The request contains invalid fields", request);
        body.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> onConstraintViolation(
            org.springframework.dao.DataIntegrityViolationException e, HttpServletRequest request) {
        log.debug("Database constraint rejected a request to {}: {}",
                request.getRequestURI(), e.getMessage());
        // The database message names indexes and columns that mean nothing to
        // an operator, so it is logged rather than returned.
        return response(HttpStatus.CONFLICT,
                "This conflicts with something that already exists. "
                        + "Check for a duplicate name or key.", request);
    }

    /**
     * An address that matches no endpoint.
     *
     * <p>Without this the catch-all below turns every mistyped URL into a 500
     * with a stack trace -- which tells an integrator their request broke the
     * server when in fact they misspelled a path, and buries real faults in the
     * log under noise that any scanner hitting the host can generate.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNoEndpoint(
            org.springframework.web.servlet.resource.NoResourceFoundException e,
            HttpServletRequest request) {
        log.debug("No endpoint for {} {}", request.getMethod(), request.getRequestURI());
        return response(HttpStatus.NOT_FOUND,
                "No endpoint at this address.", request);
    }

    /** A known endpoint asked for with the wrong verb. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onWrongMethod(
            org.springframework.web.HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        log.debug("{} not supported for {}", request.getMethod(), request.getRequestURI());
        return response(HttpStatus.METHOD_NOT_ALLOWED,
                "This endpoint does not accept " + request.getMethod() + ".", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest request) {
        // Logged in full, returned in outline: an unexpected failure often
        // carries internal detail, and the response is not the place for it.
        log.error("Unhandled exception serving {} {}",
                request.getMethod(), request.getRequestURI(), e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "The server could not complete this request. "
                        + "The details have been logged.", request);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, message, request));
    }

    private Map<String, Object> body(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? status.getReasonPhrase() : message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
