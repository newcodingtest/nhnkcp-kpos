package com.kcp.ordersystem.order.api.response;

public record AsyncOrderResponse(
        Long orderId,
        String message
) {

    public static AsyncOrderResponse accepted(
            final Long orderId,
            final String message
    ) {
        return new AsyncOrderResponse(
                orderId,
                message
        );
    }
}