package com.kcp.ordersystem.product.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("상품 재고가 부족합니다.");
    }
    public InsufficientStockException(Long productId) {
        super(productId+" 의 상품 재고가 부족합니다.");
    }
}
