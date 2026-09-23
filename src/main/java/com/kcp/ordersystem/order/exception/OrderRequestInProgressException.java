package com.kcp.ordersystem.order.exception;

public class OrderRequestInProgressException
        extends RuntimeException {

    public OrderRequestInProgressException() {
        super("동일한 주문 요청이 처리 중입니다.");
    }
}
