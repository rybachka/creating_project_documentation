package controllers;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{id}")
    public Object getOrder(@PathVariable String id) {
        return null;
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public Object delete(@PathVariable String id) {
        return null;
    }

    @PostMapping("/{orderId}/items")
    public Object addItem(
            @PathVariable String orderId,
            @RequestParam String sku,
            @RequestParam Integer qty
    ) {
        return null;
    }
}
