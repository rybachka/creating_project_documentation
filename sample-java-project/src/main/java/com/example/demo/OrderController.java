package controllers;

import org.springframework.web.bind.annotation.*;
import models.UserResponse;

/**
 * Operacje na zamówieniach.
 * Typowe błędy: 404 (zamówienie nie istnieje), 401 (brak autoryzacji).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /**
     * Pobiera zamówienie po ID.
     *
     * @param id Identyfikator zamówienia.
     * @return Szczegóły zamówienia.
     */
    @GetMapping("/{id}")
    public Object getOrder(@PathVariable String id) { return null; }

    /**
     * Usuwa zamówienie (przykład użycia RequestMapping z metodą).
     *
     * @param id Identyfikator zamówienia.
     * @return Wynik operacji.
     */
    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public Object delete(@PathVariable String id) { return null; }

    /**
     * Dodaje pozycję do zamówienia.
     *
     * @param orderId Identyfikator zamówienia.
     * @param sku Kod produktu.
     * @param qty Ilość.
     * @return Zaktualizowane zamówienie.
     */
    @PostMapping("/{orderId}/items")
    public Object addItem(
            @PathVariable String orderId,
            @RequestParam String sku,
            @RequestParam Integer qty
    ) { return null; }
}
