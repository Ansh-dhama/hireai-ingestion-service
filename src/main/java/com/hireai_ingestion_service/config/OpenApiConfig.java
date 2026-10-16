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
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("HireAI Intake and Public Evidence API")
                                .version("2.0")
                                .description(
                                        "Candidate intake and asynchronous public repository extraction. Extraction"
                                                + " routes require the application-specific bearer token returned by"
                                                + " intake.")
                                .contact(
                                        new Contact()
                                                .name("Platform Engineering Team")
                                                .email("engineering@hireai.internal"))
                                .license(new License().name("Proprietary").url("https://hireai.internal/terms")));
    }
}
