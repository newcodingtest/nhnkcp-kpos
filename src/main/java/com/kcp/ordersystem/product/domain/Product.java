package com.kcp.ordersystem.product.domain;

import com.kcp.ordersystem.product.exception.InsufficientStockException;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private Integer stockQuantity;

    @Column(nullable = false)
    private String category;

    protected Product() {
    }

    @Builder
    private Product(
            final String name,
            final Long price,
            final Integer stockQuantity,
            final String category
    ) {
        validatePrice(price);
        validateStockQuantity(stockQuantity);

        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.category = category;
    }

    public static Product create(
            final String name,
            final Long price,
            final Integer stockQuantity,
            final String category

    ){
        return Product.builder()
                .name(name)
                .price(price)
                .stockQuantity(stockQuantity)
                .category(category)
                .build();
    }
    public void update(
            final String name,
            final Long price,
            final Integer stockQuantity,
            final String category
    ) {
        validatePrice(price);
        validateStockQuantity(stockQuantity);

        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.category = category;
    }

    public void decreaseStock(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감할 재고 수량은 1개 이상이어야 합니다.");
        }

        if (stockQuantity < quantity) {
            throw new InsufficientStockException();
        }

        this.stockQuantity -= quantity;
    }

    public void increaseStock(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("복구할 재고 수량은 1개 이상이어야 합니다.");
        }

        this.stockQuantity += quantity;
    }

    private void validatePrice(final Long price) {
        if (price == null || price < 0) {
            throw new IllegalArgumentException("상품 가격은 0 이상이어야 합니다.");
        }
    }

    private void validateStockQuantity(final Integer stockQuantity) {
        if (stockQuantity == null || stockQuantity < 0) {
            throw new IllegalArgumentException("상품 재고는 0 이상이어야 합니다.");
        }
    }
}
