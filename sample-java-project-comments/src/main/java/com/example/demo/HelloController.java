package com.example.demo;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

// LEADING: prosty endpoint powitalny bez autoryzacji
@RestController
public class HelloController {

    /**
     * Zwraca przywitanie.
     * Typowe kody: 200.
     * @param name (opcjonalnie) imię użytkownika, np. /hello?name=Anna
     * @return {"message": "Hello, <name>"}
     */
    @GetMapping("/hello")
    public Map<String, String> hello(@RequestParam(required = false) String name) {
        // INLINE: ustaw domyślną wartość gdy brak name
        // TODO: dodać audit log
        String who = (name == null || name.isBlank()) ? "World" : name; // LINIA: trim logic
        return Map.of("message", "Hello, " + who); /* INLINE-BLOCK: prosta odpowiedź */
    }
}
