package com.example.certmanager.controller;

import com.example.certmanager.model.Certificate;
import com.example.certmanager.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/certificates")
@Tag(name = "Certificate API", description = "Manage certificate metadata")
public class CertificateController {

    @Autowired
    private CertificateService certificateService;

    @PostMapping
    @Operation(summary = "Save certificate metadata")
    public Certificate save(@RequestParam String clientId, @RequestBody String metadata) {
        return certificateService.saveCertificate(clientId, metadata);
    }

    @GetMapping("/{clientId}")
    @Operation(summary = "Retrieve certificate by client ID")
    public Certificate get(@PathVariable String clientId) {
        return certificateService.getCertificate(clientId);
    }
}