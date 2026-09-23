package com.kcp.ordersystem.order.api.response;

import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderStatus status,
        LocalDateTime createdAt,
        List<OrderItemResponse> items
) {

    public static OrderResponse from(final Order order) {
        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getOrderItems()
                        .stream()
                        .map(OrderItemResponse::from)
                        .toList()
        );
    }
}
