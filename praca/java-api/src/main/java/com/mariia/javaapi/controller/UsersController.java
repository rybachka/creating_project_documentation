package com.mariia.javaapi.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Content;


import com.mariia.javaapi.dto.CreateUserRequest;
import com.mariia.javaapi.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.RequestBody;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;


@Tag(name = "Users", description = "Operacje na uzytkownikach")
@RestController
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsersController {

    @Operation(summary = "Pobierz uzytkownika po ID",
    description = "Zwraca rekord uzytkownika o podanym identyfikatorze")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable String id) {
        //DEMO, zwracamy przykladowe dane(tu nie ma bazy)
        
        var u= new UserResponse();
        u.id=id;
        u.name="Demo User";
        u.email="demo@gmail.com";
        return u;

    }
    
    @Operation(summary = "Utworz uzytkownika",
    description = "Waliduje dane wejsciowe i tworzy nowego uzytkownika")
    @ApiResponse(
        responseCode = "201",
        description = "Utworzono uzytkownka",
        content = @Content(
            schema = @Schema(implementation = UserResponse.class),
            examples = @ExampleObject(
                name = "created",
                value = "{\"id\":\"a1b2c3\",\"name\":\"Jan Kowalski\",\"email\":\"jan@example.com\"}"
            )
        )
    )
    @ApiResponse(responseCode = "400", description = "Blad walidacji danych")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req){
        //DEMO, generujemy ID i "tworzymy" obiekt

        var u=new UserResponse();
        u.id = UUID.randomUUID().toString().substring(0, 6);
        u.name =req.name;
        u.email = req.email;

        //201 Created + Location: api/users/{id}
        return ResponseEntity
            .created(URI.create("/api/users/"+u.id))
            .body(u);
    }
}
