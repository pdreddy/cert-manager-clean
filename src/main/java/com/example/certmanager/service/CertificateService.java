package com.example.certmanager.service;

import com.example.certmanager.model.Certificate;
import com.example.certmanager.repository.CertificateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.stereotype.Service;

@EnableCaching
@Service
public class CertificateService {

    @Autowired
    private CertificateRepository certificateRepository;

    @CachePut(value = "certificates", key = "#clientId")
    public Certificate saveCertificate(String clientId, String metadata) {
        Certificate cert = new Certificate(clientId, metadata);
        return certificateRepository.save(cert);
    }

    @Cacheable(value = "certificates", key = "#clientId")
    public Certificate getCertificate(String clientId) {
        System.err.println("Fetching from DB for: " + clientId); // will print only once if cached
        return certificateRepository.findById(clientId).orElse(null);
    }
}