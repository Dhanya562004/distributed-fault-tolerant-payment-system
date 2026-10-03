package com.paymentsystem.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Fault-Tolerant Payment System API")
                        .version("1.0.0")
                        .description("Production-Grade Distributed Fault-Tolerant Payment & Transaction Processing System (Spring Boot + Partitioned Async Workers + Idempotency Engine)")
                        .contact(new Contact()
                                .name("Payment Engineering Team")
                                .email("engineering@paymentsystem.internal"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
