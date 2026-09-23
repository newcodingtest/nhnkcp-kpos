package com.kcp.ordersystem.common.infrastructure.memorydb;

public record IdempotencyEntry(
        Status status,
        Long orderId
) {

    public static IdempotencyEntry processing() {
        return new IdempotencyEntry(
                Status.PROCESSING,
                null
        );
    }

    public static IdempotencyEntry completed(final Long orderId) {
        return new IdempotencyEntry(
                Status.COMPLETED,
                orderId
        );
    }

    public enum Status {
        /** DB에서 추문 처리중. */
        PROCESSING,
        /** DB에서 주문 처리완료. */
        COMPLETED
    }
}
