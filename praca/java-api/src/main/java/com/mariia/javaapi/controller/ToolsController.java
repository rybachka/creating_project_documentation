package com.mariia.javaapi.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mariia.javaapi.parse.EndpointIntrospector;
import com.mariia.javaapi.parse.EndpointMeta;

@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final EndpointIntrospector introspector;

    public ToolsController(EndpointIntrospector introspector) {
        this.introspector = introspector;
    }

    @GetMapping("/endpoints")
    public List<EndpointMeta> endpoints() {
        return introspector.listAll();
    }
}
