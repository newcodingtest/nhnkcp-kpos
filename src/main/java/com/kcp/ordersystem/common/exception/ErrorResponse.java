package com.kcp.ordersystem.common.exception;

public record ErrorResponse(
        String code,
        String message
) {
}
