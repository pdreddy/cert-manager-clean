package com.example.certmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching

public class CertManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CertManagerApplication.class, args);
    }
}