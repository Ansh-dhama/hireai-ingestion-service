package com.hireai_ingestion_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.hireai_ingestion_service")
public class HireAiIngestionServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(HireAiIngestionServiceApplication.class, args);
	}
}