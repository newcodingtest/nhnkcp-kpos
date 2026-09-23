package com.kcp.ordersystem.product.api.response;

import com.kcp.ordersystem.product.domain.Product;

public record ProductResponse(
        Long id,
        String name,
        Long price,
        Integer stockQuantity,
        String category
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getCategory()
        );
    }
}