package com.mariia.javaapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Rekord uzytkownika")
public class UserResponse {

    @Schema(description = "Identyfikator", example = "a1b2c3")
    public String id;

    @Schema(description = "Imie i nazwisko", example = "Maria Rybak")
    public String name;

    @Schema(description = "Email", example = "marjarybak@gmail.com")
    public String email;
    
}
