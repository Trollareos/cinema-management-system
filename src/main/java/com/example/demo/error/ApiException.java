package com.example.demo.error;

import org.springframework.http.HttpStatus;

/**
 * Runtime exception used to return controlled HTTP errors.
 *
 * NOTE: Flexible on purpose so you can also throw:
 *   new ApiException(400, "USERNAME_REQUIRED");
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /** Main constructor (recommended). */
    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.code = code != null ? code : "ERROR";
    }

    /** With custom message. */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.code = code != null ? code : "ERROR";
    }

    /** Compatibility: allow int status codes (e.g. 400, 404, 409). */
    public ApiException(int statusCode, String code) {
        this(resolveStatus(statusCode), code);
    }

    /** Compatibility: if you used reversed order somewhere. */
    public ApiException(String code, HttpStatus status) {
        this(status, code);
    }

    private static HttpStatus resolveStatus(int statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode);
        return resolved != null ? resolved : HttpStatus.BAD_REQUEST;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
