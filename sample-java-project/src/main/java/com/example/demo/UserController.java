package controllers;

import org.springframework.web.bind.annotation.*;
import models.CreateUserRequest;
import models.UserResponse;

/**
 * Operacje na użytkownikach.
 * Typowe błędy: 400 (niepoprawne dane), 404 (nie znaleziono).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * Zwraca użytkownika po ID.
     *
     * @param id Identyfikator użytkownika.
     * @return Obiekt użytkownika.
     */
    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable String id) { return null; }

    /**
     * Wyszukuje użytkowników. Zwraca stronę wyników.
     *
     * @param q Fraza wyszukiwania.
     * @param page Numer strony.
     * @param size Rozmiar strony.
     * @return Lista użytkowników.
     */
    @GetMapping
    public Object search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size
    ) { return null; }

    /**
     * Tworzy nowego użytkownika. Zwraca 201 przy powodzeniu.
     * Typowe błędy: 400, 409.
     *
     * @param body Dane użytkownika.
     * @return Nowo utworzony użytkownik.
     */
    @PostMapping
    public UserResponse create(@RequestBody CreateUserRequest body) { return null; }
}
