package com.pm.api;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/_internal")
@RequiredArgsConstructor
public class ShutdownController {

    private final ApplicationContext ctx;

    /** Trigger a clean Spring shutdown so @PreDestroy runs (which stops all child projects). */
    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown() {
        // Reply first, then exit on a background thread.
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            int code = SpringApplication.exit(ctx, () -> 0);
            System.exit(code);
        }, "pm-shutdown").start();
        return ResponseEntity.ok(Map.of("status", "shutting-down"));
    }
}
