package controllers;

import org.springframework.web.bind.annotation.*;
import models.CreateUserRequest;
import models.UserResponse;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable String id) {
        return null;
    }

    @GetMapping
    public Object search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size
    ) {
        return null;
    }

    @PostMapping
    public UserResponse create(@RequestBody CreateUserRequest body) {
        return null;
    }
}
