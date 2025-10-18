package com.mariia.javaapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration 
public class OpenApiConfig{

    @Bean
    public OpenAPI apiInfo(){
        return new OpenAPI().info(
            new Info()
                .title("AI Docs - Java API(Etap 1)")
                .version("v1")
                .description(
                    "Szkielet dokumentacji API generowany automatycznie z kodu(springdoc)."
                )
                .contact(new Contact()
                    .name("Mariia Rybak")
                    .email("marjarybak@gmail.com")
                )
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT"))
                
        );
    }
}
