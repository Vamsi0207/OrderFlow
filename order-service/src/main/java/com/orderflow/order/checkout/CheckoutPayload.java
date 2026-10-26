package com.orderflow.order.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;

/** Versioned canonical representation: item order and split duplicate lines are immaterial. */
public record CheckoutPayload(String normalized, String hash) {
    public static CheckoutPayload from(CheckoutRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("Checkout must contain items");
        var quantities = new TreeMap<UUID, Integer>();
        for (var item : request.items()) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0)
                throw new IllegalArgumentException("Product and positive quantity are required");
            quantities.merge(item.productId(), item.quantity(), Math::addExact);
        }
        var normalized = new StringBuilder("checkout-v1\n");
        quantities.forEach((id, quantity) -> normalized.append(id).append(':').append(quantity).append('\n'));
        try {
            return new CheckoutPayload(normalized.toString(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalized.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
