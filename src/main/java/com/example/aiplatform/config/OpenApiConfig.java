package com.example.aiplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiPlatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI-Powered Support & Knowledge Platform")
                .description("Learning project demonstrating AI concepts on a Spring Boot backend")
                .version("v0.1"));
    }
}
