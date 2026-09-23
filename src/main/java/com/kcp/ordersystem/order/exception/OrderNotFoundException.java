package com.kcp.ordersystem.order.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(final Long orderId) {
        super("주문을 찾을 수 없습니다. orderId=" + orderId);
    }
}
