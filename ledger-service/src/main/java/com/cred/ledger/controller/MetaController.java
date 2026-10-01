package com.cred.ledger.controller;

import com.cred.ledger.dto.MetaResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta")
public class MetaController {

    private final boolean demoMode;
    private final String version;

    public MetaController(@Value("${app.demo-mode:false}") boolean demoMode,
                          @Value("${app.version:dev}") String version) {
        this.demoMode = demoMode;
        this.version = version;
    }

    @GetMapping
    public ResponseEntity<MetaResponse> meta() {
        return ResponseEntity.ok(new MetaResponse(demoMode, version));
    }
}
