package com.mariia.javaapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Dane potrzebne do utworzenia uzytkownika")
public class CreateUserRequest {

    @Schema(description = "Imie i nazwisko", example = "Maria Rybak")
    @NotBlank @Size(min=2, max=60)
    public String name;

    @Schema(description = "Email uzytkownika", example = "marjarybak@gmail.com")
    @Email @NotBlank
    public String email;
    
}
