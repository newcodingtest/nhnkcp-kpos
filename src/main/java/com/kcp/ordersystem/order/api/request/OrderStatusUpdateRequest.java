package com.kcp.ordersystem.order.api.request;

import com.kcp.ordersystem.order.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(

        @NotNull(message = "주문 상태는 필수입니다.")
        OrderStatus status
) {
}
