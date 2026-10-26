package com.orderflow.order.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Only client intent: prices and totals are deliberately absent. */
public record CheckoutRequest(@NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull UUID productId, @NotNull @Positive Integer quantity) {}
}
