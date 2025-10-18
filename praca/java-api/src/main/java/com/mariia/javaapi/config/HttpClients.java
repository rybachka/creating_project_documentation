package com.mariia.javaapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpClients {

    @Bean
    WebClient nlpClient(@Value("${nlp.url:http://python-nlp:8000}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
