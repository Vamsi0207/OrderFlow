package com.orderflow.inventory.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record ReservationRequest(@NotEmpty List<@NotNull @Valid Item> items) {
    public record Item(@NotNull UUID productId, @NotNull @Positive Integer quantity) {}

    public SortedMap<UUID, Integer> normalizedItems() {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Items required");
        var normalized = new TreeMap<UUID, Integer>();
        for (var item : items) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0)
                throw new IllegalArgumentException("Product and positive quantity required");
            normalized.merge(item.productId(), item.quantity(), Math::addExact);
        }
        return normalized;
    }
}
