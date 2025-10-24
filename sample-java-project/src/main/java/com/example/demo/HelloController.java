package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;


@RestController 
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello(@RequestParam(required = false) String name) {
      
        String who = (name == null || name.isBlank()) ? "World" : name;

      
        return Map.of("message", "Hello, " + who);
    }
}
