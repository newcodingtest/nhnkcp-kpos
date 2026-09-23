package com.kcp.ordersystem.order.api.response;

import com.kcp.ordersystem.order.domain.OrderItem;

public record OrderItemResponse(
        Long productId,
        String productName,
        Long orderPrice,
        Integer quantity
) {

    public static OrderItemResponse from(final OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getProductId(),
                orderItem.getProductName(),
                orderItem.getOrderPrice(),
                orderItem.getQuantity()
        );
    }
}
