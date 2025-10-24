package controllers;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /**
     * Pobiera zamówienie po ID.
     * Typowe kody: 200, 404.
     * @param id identyfikator zamówienia (UUID)
     */
    @GetMapping("/{id}")
    public Object getOrder(@PathVariable String id) {
        // VALIDATION: sprawdź format UUID
        // TODO: dodać audit log
        return null;
    }

    /* LEADING-BLOCK: usuwanie zamówienia (soft delete) */
    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public Object delete(@PathVariable String id) {
        // PERMS: sprawdź uprawnienia
        return null;
    }

    /**
     * Dodaje pozycję do zamówienia.
     * @param orderId identyfikator zamówienia
     * @param sku kod produktu (SKU)
     * @param qty ilość (>0)
     */
    @PostMapping("/{orderId}/items")
    public Object addItem(@PathVariable String orderId,
                          @RequestParam String sku,
                          @RequestParam Integer qty) {
        // CHECK: qty > 0
        /* STOCK: sprawdź dostępność SKU */
        return null;
    }
}
