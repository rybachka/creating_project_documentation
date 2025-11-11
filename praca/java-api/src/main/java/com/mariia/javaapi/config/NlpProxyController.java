package com.mariia.javaapi.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/nlp")
public class NlpProxyController {

    private final WebClient nlpClient;
    private final Duration timeout = Duration.ofSeconds(90);

    public NlpProxyController(@Qualifier("nlpClient") WebClient nlpClient) {
        this.nlpClient = nlpClient;
    }

    /**
     * Proxy do debugowego endpointu w Pythonie.
     * Front wysyła JSON z DescribeIn/NlpInputEntry.
     * Zwracamy dokładnie to, co Python zwróci ({ "prompt": "...", "raw": "..." }).
     */
    @PostMapping(value = "/output-preview", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> outputPreview(@RequestBody Map<String, Object> input) {
        // audience/mode bierzemy z wejścia jeśli są, inaczej domyślne
        String audience = String.valueOf(input.getOrDefault("audience", "beginner"));
        String mode = String.valueOf(input.getOrDefault("mode", "ollama"));

        Map<String, Object> res = nlpClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/nlp/output-preview")
                        .queryParam("audience", audience)
                        .queryParam("mode", mode)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(timeout);

        if (res == null) {
            // w razie czego zwróć pustą strukturę zamiast 500,
            // front i tak pokazuje "prompt" i "raw" 1:1
            return ResponseEntity.ok(Map.of(
                    "prompt", "",
                    "raw", ""
            ));
        }

        return ResponseEntity.ok(res);
    }
}
