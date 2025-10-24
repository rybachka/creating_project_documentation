package controllers;

import org.springframework.web.bind.annotation.*;
import models.CreateUserRequest;
import com.example.demo.dto.UserResponse;

/**
 * Operacje na użytkownikach (CRUD).
 * Statusy: 200, 201, 400, 404, 409.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * Zwraca użytkownika po ID.
     * @param id identyfikator użytkownika
     * @return UserResponse
     */
    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable String id) {
        // LOOKUP: repo.findById
        return null;
    }

    /**
     * Tworzy nowego użytkownika.
     * Typowe kody: 201 (Created), 400, 409.
     * @param body dane użytkownika
     * @return UserResponse
     */
    @PostMapping
    public UserResponse create(@RequestBody CreateUserRequest body) {
        // VALIDATE: email format
        // CONFLICT: email unique
        return null;
    }
}
