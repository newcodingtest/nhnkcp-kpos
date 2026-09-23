package com.kcp.ordersystem.order.exception;

import com.kcp.ordersystem.order.domain.OrderStatus;

public class OrderStatusChangeInProgressException
        extends RuntimeException {

    public OrderStatusChangeInProgressException(
            final Long orderId,
            final OrderStatus processingStatus
    ) {
        super(
                "주문 상태 변경이 이미 처리 중입니다. orderId="
                        + orderId
                        + ", processingStatus="
                        + processingStatus
        );
    }
}
