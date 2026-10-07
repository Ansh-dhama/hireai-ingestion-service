package com.hireai_ingestion_service.config;

import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.OpenAPI;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI  customOpenAPI() {
        return new OpenAPI()
                .info( new Info()
                        .title("Autonomous Ai talent ingestion Engine Api")
                        .version("1.0")
                        .description("High-throughput streaming candidate ingestion, Tika sanitization, and GitHub verification.")
                        .contact(new Contact().name("Platform Engineering Team").email("engineering@hireai.internal"))
                        .license(new License().name("Proprietary").url("https://hireai.internal/terms")));

    }
}
