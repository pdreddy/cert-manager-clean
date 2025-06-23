package com.damu.certificatemanager.service;

import com.damu.certificatemanager.dto.CertificateRequest;
import com.damu.certificatemanager.dto.CertificateResponse;
import com.damu.certificatemanager.entity.Certificate;
import com.damu.certificatemanager.repository.CertificateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class CertificateService {

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    @Qualifier("redisTemplate")
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("objectRedisTemplate")
    private RedisTemplate<String, Object> objectRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "cert:";
    private static final String CLIENT_PREFIX = "client:";
    private static final String LOOKUP_PREFIX = "lookup:";
    private static final long CACHE_TIMEOUT = 24; // hours

    public CertificateResponse saveCertificate(CertificateRequest request) {
        try {
            // Parse certificate
            X509Certificate x509Cert = parseCertificate(request.getCertText());
            
            // Extract certificate information
            String serialNumber = x509Cert.getSerialNumber().toString();
            String subject = x509Cert.getSubjectDN().toString();
            String issuer = x509Cert.getIssuerDN().toString();
            LocalDateTime startDate = x509Cert.getNotBefore().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime expiryDate = x509Cert.getNotAfter().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
            String algorithm = x509Cert.getSigAlgName();
            Integer version = x509Cert.getVersion();
            String fingerprint = generateFingerprint(x509Cert.getEncoded());

            // Check if certificate already exists
            if (certificateRepository.existsByClientIdAndSerialNumber(request.getClientId(), serialNumber)) {
                throw new RuntimeException("Certificate already exists for this client");
            }

            // Create and save certificate entity
            Certificate certificate = new Certificate(
                request.getClientId(),
                serialNumber,
                subject,
                issuer,
                startDate,
                expiryDate,
                algorithm,
                version,
                request.getCertText(),
                fingerprint
            );

            Certificate savedCert = certificateRepository.save(certificate);
            log.info("Certificate saved to database for client: {} with serial: {}", 
                    request.getClientId(), serialNumber);

            // Cache the certificate in Redis cluster
            cacheCertificateInCluster(savedCert);

            return new CertificateResponse(savedCert);

        } catch (Exception e) {
            log.error("Failed to process certificate for client: {}", request.getClientId(), e);
            throw new RuntimeException("Failed to process certificate: " + e.getMessage(), e);
        }
    }

    public List<CertificateResponse> getCertificatesByClientId(String clientId) {
        // Try to get from Redis cluster first (reads from replica preferentially)
        String cacheKey = CACHE_PREFIX + CLIENT_PREFIX + clientId;
        
        try {
            String cachedData = redisTemplate.opsForValue().get(cacheKey);
            if (cachedData != null) {
                List<Certificate> certificates = objectMapper.readValue(cachedData, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Certificate.class));
                log.debug("Retrieved {} certificates from Redis cluster for client: {}", certificates.size(), clientId);
                return certificates.stream()
                    .map(CertificateResponse::new)
                    .collect(Collectors.toList());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached data from Redis cluster for client: {}", clientId, e);
        } catch (Exception e) {
            log.warn("Redis cluster read failed for client: {}, falling back to database", clientId, e);
        }

        // Get from database
        List<Certificate> certificates = certificateRepository.findByClientIdOrderByCreatedAtDesc(clientId);
        log.info("Retrieved {} certificates from database for client: {}", certificates.size(), clientId);
        
        // Cache the result in Redis cluster (writes to master)
        try {
            String dataToCache = objectMapper.writeValueAsString(certificates);
            redisTemplate.opsForValue().set(cacheKey, dataToCache, CACHE_TIMEOUT, TimeUnit.HOURS);
            log.debug("Cached {} certificates in Redis cluster for client: {}", certificates.size(), clientId);
        } catch (JsonProcessingException e) {
            log.error("Failed to cache data in Redis cluster for client: {}", clientId, e);
        } catch (Exception e) {
            log.warn("Redis cluster write failed for client: {}", clientId, e);
        }

        return certificates.stream()
            .map(CertificateResponse::new)
            .collect(Collectors.toList());
    }

    public Optional<CertificateResponse> getCertificateByClientIdAndCertText(String clientId, String certText) {
        String cacheKey = CACHE_PREFIX + LOOKUP_PREFIX + clientId + ":" + certText.hashCode();
        
        try {
            // Try Redis cluster first (reads from replica)
            String cachedData = redisTemplate.opsForValue().get(cacheKey);
            if (cachedData != null) {
                Certificate certificate = objectMapper.readValue(cachedData, Certificate.class);
                log.debug("Retrieved certificate from Redis cluster for client: {}", clientId);
                return Optional.of(new CertificateResponse(certificate));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached certificate from Redis cluster for client: {}", clientId, e);
        } catch (Exception e) {
            log.warn("Redis cluster lookup failed for client: {}, checking database", clientId, e);
        }

        Optional<Certificate> certificate = certificateRepository
            .findByClientIdAndCertText(clientId, certText);
        
        if (certificate.isPresent()) {
            log.info("Found certificate in database for client: {}", clientId);
            // Cache the certificate in Redis cluster
            cacheCertificateInCluster(certificate.get());
            return Optional.of(new CertificateResponse(certificate.get()));
        }
        
        log.warn("Certificate not found for client: {}", clientId);
        return Optional.empty();
    }

    public void deleteCertificate(Long id) {
        Optional<Certificate> certificate = certificateRepository.findById(id);
        if (certificate.isPresent()) {
            Certificate cert = certificate.get();
            
            // Remove from Redis cluster cache
            String clientCacheKey = CACHE_PREFIX + CLIENT_PREFIX + cert.getClientId();
            String lookupCacheKey = CACHE_PREFIX + LOOKUP_PREFIX + cert.getClientId() + ":" + cert.getCertText().hashCode();
            
            try {
                redisTemplate.delete(clientCacheKey);
                redisTemplate.delete(lookupCacheKey);
                log.debug("Removed certificate cache from Redis cluster for client: {}", cert.getClientId());
            } catch (Exception e) {
                log.warn("Failed to remove certificate from Redis cluster cache: {}", e.getMessage());
            }
            
            // Delete from database
            certificateRepository.deleteById(id);
            log.info("Certificate deleted successfully for id: {}", id);
        } else {
            log.warn("Certificate not found for deletion with id: {}", id);
        }
    }

    private X509Certificate parseCertificate(String certText) throws Exception {
        String cleanCert = certText
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");

        byte[] certBytes = Base64.getDecoder().decode(cleanCert);
    private X509Certificate parseCertificate(String certText) throws Exception {
        String cleanCert = certText
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");

        byte[] certBytes = Base64.getDecoder().decode(cleanCert);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    private String generateFingerprint(byte[] certBytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(certBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void cacheCertificateInCluster(Certificate certificate) {
        try {
            String clientCacheKey = CACHE_PREFIX + CLIENT_PREFIX + certificate.getClientId();
            String lookupCacheKey = CACHE_PREFIX + LOOKUP_PREFIX + certificate.getClientId() + ":" + certificate.getCertText().hashCode();
            
            // Cache individual certificate for lookup
            String certData = objectMapper.writeValueAsString(certificate);
            redisTemplate.opsForValue().set(lookupCacheKey, certData, CACHE_TIMEOUT, TimeUnit.HOURS);
            
            // Invalidate client list cache to ensure fresh data on next request
            redisTemplate.delete(clientCacheKey);
            
            log.debug("Cached certificate in Redis cluster for client: {}", certificate.getClientId());
            
        } catch (JsonProcessingException e) {
            log.error("Failed to cache certificate in Redis cluster for client: {}", certificate.getClientId(), e);
        } catch (Exception e) {
            log.warn("Redis cluster caching failed for client: {}", certificate.getClientId(), e);
        }
    }
}
