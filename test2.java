// ===============================
// 1. application.yml (Certificate Manager with Redis Cluster - Password Only)
// ===============================
spring:
  application:
    name: certificate-manager
  profiles:
    active: dev
  
  # Server Configuration
  server:
    port: 8080

  # H2 Database Configuration
  datasource:
    url: jdbc:h2:mem:certdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driverClassName: org.h2.Driver
    username: sa
    password: password

  # JPA Configuration
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
    show-sql: false
    properties:
      hibernate:
        format_sql: true

  # H2 Console
  h2:
    console:
      enabled: true
      path: /h2-console
  
  # Redis Cluster Configuration (1 Master + 1 Replica) - Password Authentication Only
  redis:
    cluster:
      enabled: true
      nodes: ${REDIS_CLUSTER_NODES:master.cache.amazonaws.com:6379,replica.cache.amazonaws.com:6379}
      max-redirects: 2
      
    # Password Authentication (No Auth Token)
    password: ${REDIS_PASSWORD:your-redis-password}
    
    # Connection Settings
    timeout: 5000ms
    ssl: ${REDIS_SSL:true}
    
    # Pool Settings
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
        max-wait: 2000ms

  # Jackson Configuration
  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC

# Logging
logging:
  level:
    com.damu.certificatemanager: INFO
    org.springframework.data.redis: WARN
    root: INFO

---
# Development Profile (Local Redis - No Password)
spring:
  config:
    activate:
      on-profile: dev
  redis:
    cluster:
      nodes: localhost:7000,localhost:7001
    password: ""  # No password for local development
    ssl: false
  jpa:
    show-sql: true

---
# Production Profile (AWS ElastiCache - With Password)
spring:
  config:
    activate:
      on-profile: prod
  redis:
    cluster:
      nodes: ${REDIS_CLUSTER_NODES}
    password: ${REDIS_PASSWORD}  # Password from environment variable
    ssl: true
  jpa:
    show-sql: false
  h2:
    console:
      enabled: false

// ===============================
// 2. RedisClusterConfig.java (Password Authentication Only)
// ===============================
package com.damu.certificatemanager.config;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.SslOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Arrays;

@Configuration
@Slf4j
public class RedisClusterConfig {

    @Value("${spring.redis.cluster.nodes}")
    private String clusterNodes;

    @Value("${spring.redis.cluster.max-redirects:2}")
    private int maxRedirects;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.timeout:5000ms}")
    private Duration timeout;

    @Value("${spring.redis.ssl:true}")
    private boolean sslEnabled;

    @Value("${spring.redis.lettuce.pool.max-active:20}")
    private int maxActive;

    @Value("${spring.redis.lettuce.pool.max-idle:10}")
    private int maxIdle;

    @Value("${spring.redis.lettuce.pool.min-idle:5}")
    private int minIdle;

    @Value("${spring.redis.lettuce.pool.max-wait:2000ms}")
    private Duration maxWait;

    @Bean
    public RedisClusterConfiguration redisClusterConfiguration() {
        log.info("Configuring Redis Cluster for Certificate Manager with nodes: {}", clusterNodes);
        
        RedisClusterConfiguration config = new RedisClusterConfiguration(Arrays.asList(clusterNodes.split(",")));
        config.setMaxRedirects(maxRedirects);
        
        // Password Authentication Only
        if (!redisPassword.isEmpty()) {
            config.setPassword(RedisPassword.of(redisPassword));
            log.info("Redis cluster password authentication configured for certificate manager");
        } else {
            log.info("Redis cluster configured without authentication (development mode)");
        }
        
        return config;
    }

    @Bean(name = "redisConnectionFactory")
    @Primary
    public LettuceConnectionFactory redisConnectionFactory() {
        log.info("Creating Redis cluster connection factory for certificates (SSL: {}, Password: {})", 
                sslEnabled, !redisPassword.isEmpty() ? "YES" : "NO");
        
        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .commandTimeout(timeout)
            .poolConfig(createPoolConfig())
            .readFrom(ReadFrom.REPLICA_PREFERRED) // Read certificates from replica when possible
            .clientOptions(createClientOptions())
            .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisClusterConfiguration(), clientConfig);
        factory.setValidateConnection(true);
        factory.afterPropertiesSet();
        
        log.info("Redis cluster connection factory created for certificate storage");
        return factory;
    }

    @Bean(name = "redisTemplate")
    @Primary
    public RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        
        // Serializers optimized for certificate data
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        
        template.setEnableTransactionSupport(false); // Not supported in cluster
        template.afterPropertiesSet();
        
        log.info("Redis cluster template configured for certificate caching (SSL: {}, Auth: {})", 
                sslEnabled, !redisPassword.isEmpty() ? "Password" : "None");
        return template;
    }

    @Bean(name = "objectRedisTemplate")
    public RedisTemplate<String, Object> objectRedisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        
        // Serializers for complex certificate objects
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();
        
        log.info("Object Redis template configured for certificate objects");
        return template;
    }

    private ClusterClientOptions createClientOptions() {
        ClusterClientOptions.Builder builder = ClusterClientOptions.builder()
            .validateClusterNodeMembership(false) // For 2-node setup
            .topologyRefreshOptions(
                ClusterTopologyRefreshOptions.builder()
                    .enablePeriodicRefresh(Duration.ofSeconds(30))
                    .enableAllAdaptiveRefreshTriggers()
                    .build()
            );

        if (sslEnabled) {
            builder.sslOptions(SslOptions.builder().jdkSslProvider().build());
            log.info("SSL enabled for certificate Redis cluster");
        }

        return builder.build();
    }

    private org.apache.commons.pool2.impl.GenericObjectPoolConfig createPoolConfig() {
        org.apache.commons.pool2.impl.GenericObjectPoolConfig config = 
            new org.apache.commons.pool2.impl.GenericObjectPoolConfig();
        
        config.setMaxTotal(maxActive);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);
        config.setMaxWaitMillis(maxWait.toMillis());
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        config.setTestWhileIdle(true);
        
        log.info("Certificate Redis pool: Max={}, MaxIdle={}, MinIdle={}", maxActive, maxIdle, minIdle);
        return poolConfig;
    }
}

// ===============================
// 5. Environment Variables (Password Authentication Only)
// ===============================

# Production AWS ElastiCache (Password Authentication)
REDIS_CLUSTER_NODES=master-001.xxxxx.cache.amazonaws.com:6379,replica-001.xxxxx.cache.amazonaws.com:6379
REDIS_PASSWORD=your-redis-cluster-password
REDIS_SSL=true

# Development (Local Redis Cluster - No Password)
# REDIS_CLUSTER_NODES=localhost:7000,localhost:7001
# REDIS_PASSWORD=
# REDIS_SSL=false

// ===============================
// 6. Complete Certificate Manager Application Files
// ===============================

// CertificateManagerApplication.java
package com.damu.certificatemanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableTransactionManagement
public class CertificateManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CertificateManagerApplication.class, args);
    }
}

// CertificateRequest.java
package com.damu.certificatemanager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CertificateRequest {
    
    @NotBlank(message = "Client ID is required")
    private String clientId;
    
    @NotBlank(message = "Certificate text is required")
    private String certText;
}

// CertificateResponse.java
package com.damu.certificatemanager.dto;

import com.damu.certificatemanager.entity.Certificate;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class CertificateResponse {
    private Long id;
    private String clientId;
    private String serialNumber;
    private String subject;
    private String issuer;
    private LocalDateTime startDate;
    private LocalDateTime expiryDate;
    private String algorithm;
    private Integer version;
    private String fingerprint;
    private LocalDateTime createdAt;

    public CertificateResponse(Certificate certificate) {
        this.id = certificate.getId();
        this.clientId = certificate.getClientId();
        this.serialNumber = certificate.getSerialNumber();
        this.subject = certificate.getSubject();
        this.issuer = certificate.getIssuer();
        this.startDate = certificate.getStartDate();
        this.expiryDate = certificate.getExpiryDate();
        this.algorithm = certificate.getAlgorithm();
        this.version = certificate.getVersion();
        this.fingerprint = certificate.getFingerprint();
        this.createdAt = certificate.getCreatedAt();
    }
}

// CertificateRepository.java
package com.damu.certificatemanager.repository;

import com.damu.certificatemanager.entity.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    
    List<Certificate> findByClientId(String clientId);
    
    @Query("SELECT c FROM Certificate c WHERE c.clientId = :clientId AND c.certText = :certText")
    Optional<Certificate> findByClientIdAndCertText(@Param("clientId") String clientId, 
                                                   @Param("certText") String certText);
    
    List<Certificate> findByClientIdOrderByCreatedAtDesc(String clientId);
    
    Optional<Certificate> findBySerialNumber(String serialNumber);
    
    boolean existsByClientIdAndSerialNumber(String clientId, String serialNumber);
}

// CertificateController.java
package com.damu.certificatemanager.controller;

import com.damu.certificatemanager.dto.CertificateRequest;
import com.damu.certificatemanager.dto.CertificateResponse;
import com.damu.certificatemanager.service.CertificateService;
import com.damu.certificatemanager.service.RedisClusterHealthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/certificates")
@CrossOrigin(origins = "*")
@Slf4j
public class CertificateController {

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private RedisClusterHealthService redisHealthService;

    @PostMapping
    public ResponseEntity<?> saveCertificate(@Valid @RequestBody CertificateRequest request) {
        try {
            log.info("Received request to save certificate for client: {}", request.getClientId());
            CertificateResponse response = certificateService.saveCertificate(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error processing certificate for client: {}", request.getClientId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Error processing certificate: " + e.getMessage());
        }
    }

    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<CertificateResponse>> getCertificatesByClientId(
            @PathVariable String clientId) {
        log.info("Received request to get certificates for client: {}", clientId);
        List<CertificateResponse> certificates = certificateService.getCertificatesByClientId(clientId);
        return ResponseEntity.ok(certificates);
    }

    @GetMapping("/lookup")
    public ResponseEntity<?> getCertificateByClientIdAndCertText(
            @RequestParam String clientId,
            @RequestParam String certText) {
        try {
            log.info("Received request to lookup certificate for client: {}", clientId);
            Optional<CertificateResponse> certificate = certificateService
                .getCertificateByClientIdAndCertText(clientId, certText);
            
            if (certificate.isPresent()) {
                return ResponseEntity.ok(certificate.get());
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Certificate not found for the given client ID and certificate text");
            }
        } catch (Exception e) {
            log.error("Error retrieving certificate for client: {}", clientId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Error retrieving certificate: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteCertificate(@PathVariable Long id) {
        try {
            log.info("Received request to delete certificate with id: {}", id);
            certificateService.deleteCertificate(id);
            return ResponseEntity.ok("Certificate deleted successfully");
        } catch (Exception e) {
            log.error("Error deleting certificate with id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error deleting certificate: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Certificate service is running");
    }

    @GetMapping("/redis/health")
    public ResponseEntity<Map<String, Object>> getRedisHealth() {
        try {
            log.info("Received request to get Redis cluster health");
            Map<String, Object> health = redisHealthService.getRedisClusterHealth();
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Error getting Redis cluster health", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/redis/info")
    public ResponseEntity<Map<String, Object>> getRedisInfo() {
        try {
            log.info("Received request to get Redis cluster info");
            Map<String, Object> info = redisHealthService.getDetailedRedisInfo();
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Error getting Redis cluster info", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}

// RedisClusterHealthService.java
package com.damu.certificatemanager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Service
@Slf4j
public class RedisClusterHealthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final LettuceConnectionFactory connectionFactory;

    public RedisClusterHealthService(
            @Qualifier("redisTemplate") RedisTemplate<String, String> redisTemplate,
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
    }

    public Map<String, Object> getRedisClusterHealth() {
        Map<String, Object> health = new HashMap<>();
        
        try {
            RedisClusterConnection connection = connectionFactory.getClusterConnection();
            
            // Get cluster nodes
            Iterable<RedisClusterNode> nodes = connection.clusterGetNodes();
            
            int nodeCount = 0;
            int healthyNodes = 0;
            RedisClusterNode masterNode = null;
            RedisClusterNode replicaNode = null;
            
            for (RedisClusterNode node : nodes) {
                nodeCount++;
                if (!node.isMarkedAsFail()) {
                    healthyNodes++;
                }
                
                if (node.isMaster()) {
                    masterNode = node;
                } else {
                    replicaNode = node;
                }
            }
            
            health.put("cluster_info", Map.of(
                "total_nodes", nodeCount,
                "healthy_nodes", healthyNodes,
                "cluster_type", "1_master_1_replica",
                "authentication", "password_only"
            ));
            
            health.put("master_node", getNodeInfo(masterNode, connection));
            health.put("replica_node", getNodeInfo(replicaNode, connection));
            
            // Overall status
            if (healthyNodes == 2) {
                health.put("status", "HEALTHY");
            } else if (masterNode != null && !masterNode.isMarkedAsFail()) {
                health.put("status", "DEGRADED - Master OK, Replica Issues");
            } else {
                health.put("status", "CRITICAL - Master Issues");
            }
            
            // Test certificate operations
            health.put("certificate_operations_test", testCertificateOperations());
            
            connection.close();
            
        } catch (Exception e) {
            log.error("Error checking Redis cluster health", e);
            health.put("status", "ERROR");
            health.put("error", e.getMessage());
        }
        
        return health;
    }

    public Map<String, Object> getDetailedRedisInfo() {
        Map<String, Object> info = new HashMap<>();
        
        try {
            RedisClusterConnection connection = connectionFactory.getClusterConnection();
            
            // Cluster info
            Properties clusterInfo = connection.clusterGetClusterInfo();
            info.put("cluster_info", clusterInfo);
            
            // Node details
            Iterable<RedisClusterNode> nodes = connection.clusterGetNodes();
            Map<String, Object> nodeDetails = new HashMap<>();
            
            for (RedisClusterNode node : nodes) {
                String nodeKey = node.isMaster() ? "master" : "replica";
                nodeDetails.put(nodeKey, getDetailedNodeInfo(node, connection));
            }
            
            info.put("nodes", nodeDetails);
            info.put("authentication_method", "password");
            
            connection.close();
            
        } catch (Exception e) {
            log.error("Error getting detailed Redis info", e);
            info.put("error", e.getMessage());
        }
        
        return info;
    }

    private Map<String, Object> getNodeInfo(RedisClusterNode node, RedisClusterConnection connection) {
        if (node == null) return Map.of("status", "NOT_FOUND");
        
        Map<String, Object> nodeInfo = new HashMap<>();
        nodeInfo.put("id", node.getId());
        nodeInfo.put("host", node.getHost());
        nodeInfo.put("port", node.getPort());
        nodeInfo.put("role", node.isMaster() ? "master" : "replica");
        nodeInfo.put("healthy", !node.isMarkedAsFail());
        
        return nodeInfo;
    }

    private Map<String, Object> getDetailedNodeInfo(RedisClusterNode node, RedisClusterConnection connection) {
        Map<String, Object> info = getNodeInfo(node, connection);
        
        try {
            Properties nodeProperties = connection.info(node);
            info.put("redis_version", nodeProperties.getProperty("redis_version"));
            info.put("used_memory_human", nodeProperties.getProperty("used_memory_human"));
            info.put("uptime_seconds", nodeProperties.getProperty("uptime_in_seconds"));
            info.put("role_detailed", nodeProperties.getProperty("role"));
            
            if (!node.isMaster()) {
                info.put("master_link_status", nodeProperties.getProperty("master_link_status"));
            }
            
        } catch (Exception e) {
            info.put("info_error", e.getMessage());
        }
        
        return info;
    }

    private boolean testCertificateOperations() {
        try {
            String testKey = "cert:test:" + System.currentTimeMillis();
            String testValue = "test_certificate_data";
            
            // Write test (to master)
            redisTemplate.opsForValue().set(testKey, testValue, Duration.ofMinutes(1));
            
            // Read test (from replica preferred)
            String result = redisTemplate.opsForValue().get(testKey);
            
            // Cleanup
            redisTemplate.delete(testKey);
            
            return testValue.equals(result);
            
        } catch (Exception e) {
            log.error("Certificate operations test failed", e);
            return false;
        }
    }
}

// ===============================
// 7. Usage Examples with Password Authentication
// ===============================

# Set environment variables for production
export REDIS_CLUSTER_NODES="master-001.xxxxx.cache.amazonaws.com:6379,replica-001.xxxxx.cache.amazonaws.com:6379"
export REDIS_PASSWORD="your-secure-redis-password"
export REDIS_SSL="true"

# Run the application
mvn spring-boot:run -Dspring.profiles.active=prod

# Test the certificate endpoints:

# 1. Save certificate (cached in Redis cluster with password auth)
curl -X POST "http://localhost:8080/api/certificates" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client123",
    "certText": "-----BEGIN CERTIFICATE-----\nYour certificate content here\n-----END CERTIFICATE-----"
  }'

# 2. Get certificates by client (reads from Redis replica preferentially)
curl "http://localhost:8080/api/certificates/client/client123"

# 3. Check Redis cluster health
curl "http://localhost:8080/api/certificates/redis/health"

# 4. Get detailed Redis cluster info
curl "http://localhost:8080/api/certificates/redis/info"

# 5. Certificate lookup
curl "http://localhost:8080/api/certificates/lookup?clientId=client123&certText=your-cert-text"

# ===============================
# 8. pom.xml Dependencies
# ===============================
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.1.12</version>
        <relativePath/>
    </parent>
    <groupId>com.damu</groupId>
    <artifactId>certificate-manager</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <n>certificate-manager</n>
    <description>Certificate Management Application with Redis Cluster</description>
    <properties>
        <java.version>17</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.lettuce</groupId>
            <artifactId>lettuce-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-pool2</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>

// ===============================
// 3. Updated Certificate Entity (Same as before)
// ===============================
package com.damu.certificatemanager.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "certificates")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Certificate {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @NotBlank(message = "Client ID is required")
    @Column(name = "client_id", nullable = false)
    private String clientId;
    
    @NotBlank(message = "Serial number is required")
    @Column(name = "serial_number", nullable = false)
    private String serialNumber;
    
    @Column(name = "subject")
    private String subject;
    
    @Column(name = "issuer")
    private String issuer;
    
    @NotNull(message = "Start date is required")
    @Column(name = "start_date")
    private LocalDateTime startDate;
    
    @NotNull(message = "Expiry date is required")
    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;
    
    @Column(name = "algorithm")
    private String algorithm;
    
    @Column(name = "version")
    private Integer version;
    
    @Column(name = "cert_text", columnDefinition = "CLOB")
    private String certText;
    
    @Column(name = "fingerprint")
    private String fingerprint;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Certificate(String clientId, String serialNumber, String subject, String issuer, 
                      LocalDateTime startDate, LocalDateTime expiryDate, String algorithm, 
                      Integer version, String certText, String fingerprint) {
        this.clientId = clientId;
        this.serialNumber = serialNumber;
        this.subject = subject;
        this.issuer = issuer;
        this.startDate = startDate;
        this.expiryDate = expiryDate;
        this.algorithm = algorithm;
        this.version = version;
        this.certText = certText;
        this.fingerprint = fingerprint;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

// ===============================
// 4. Updated CertificateService.java (with Redis Cluster)
// ===============================
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
        CertificateFactory cf = CertificateFactory.
