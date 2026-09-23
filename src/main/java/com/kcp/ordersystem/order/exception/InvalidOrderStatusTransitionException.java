package com.kcp.ordersystem.order.exception;

import com.kcp.ordersystem.order.domain.OrderStatus;

public class InvalidOrderStatusTransitionException extends RuntimeException {

    public InvalidOrderStatusTransitionException(
            final OrderStatus currentStatus,
            final OrderStatus targetStatus
    ) {
        super(
                "허용되지 않은 주문 상태 변경입니다. currentStatus="
                        + currentStatus
                        + ", targetStatus="
                        + targetStatus
        );
    }
}
