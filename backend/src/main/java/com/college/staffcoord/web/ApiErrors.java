package com.college.staffcoord.web;

import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    private static ResponseEntity<Map<String, String>> body(HttpStatus s, String m) {
        return ResponseEntity.status(s).body(Map.of("error", m));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(AccessDeniedException e) { return body(HttpStatus.FORBIDDEN, "Forbidden"); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) { return body(HttpStatus.BAD_REQUEST, "Invalid request"); }

    /** 42501 = row-level-security / privilege / guard-trigger violation => 403. 23xxx = constraint => 409. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> db(DataAccessException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                if (sql.getSQLState().equals("42501")) return body(HttpStatus.FORBIDDEN, "Forbidden");
                if (sql.getSQLState().startsWith("23")) return body(HttpStatus.CONFLICT, "Conflict or invalid data");
            }
            t = t.getCause();
        }
        log.error("Database error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Server error");
    }
}
