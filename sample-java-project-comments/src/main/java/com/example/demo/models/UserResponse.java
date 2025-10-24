package com.example.demo.dto;

/**
 * Prosty DTO użytkownika do odpowiedzi API.
 * Uwaga: w praktyce rozważ:
 *  - uczynienie pól prywatnymi + gettery/settery (lub rekord w Javie 16+),
 *  - adnotacje @Schema (OpenAPI) do wygenerowania dokumentacji,
 *  - pominięcie pól wrażliwych (np. hasła) w DTO odpowiedzi.
 */
public class UserResponse {
    /** Unikalny identyfikator użytkownika (np. UUID). */
    public String id;

    /** Pełne imię i nazwisko lub wyświetlana nazwa. */
    public String name;

    /** Adres e-mail użytkownika. */
    public String email;
}
