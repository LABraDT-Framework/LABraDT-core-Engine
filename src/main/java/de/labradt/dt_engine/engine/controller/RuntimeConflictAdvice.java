package de.labradt.dt_engine.engine.controller;

import de.labradt.dt_engine.engine.RuntimeLockedException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class RuntimeConflictAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("detail", error.getReason() == null ? "Backend request rejected" : error.getReason()));
    }
    @ExceptionHandler(RuntimeLockedException.class)
    public ResponseEntity<Map<String,String>> locked(RuntimeLockedException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", error.getMessage()));
    }
}
