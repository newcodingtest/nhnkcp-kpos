package com.kcp.ordersystem.product.domain;

import com.kcp.ordersystem.product.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    @DisplayName("상품의 재고를 차감한다")
    void decreaseStock() {
        Product product = createProduct(10);

        product.decreaseStock(3);

        assertThat(product.getStockQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("현재 재고와 동일한 수량을 차감할 수 있다")
    void decreaseAllStock() {
        Product product = createProduct(10);

        product.decreaseStock(10);

        assertThat(product.getStockQuantity()).isZero();
    }

    @Test
    @DisplayName("현재 재고보다 많은 수량을 차감하면 실패한다")
    void decreaseStockFailWhenInsufficientStock() {
        Product product = createProduct(10);

        assertThatThrownBy(() -> product.decreaseStock(11))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(product.getStockQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("상품의 재고를 복구한다")
    void increaseStock() {
        Product product = createProduct(10);

        product.increaseStock(3);

        assertThat(product.getStockQuantity()).isEqualTo(13);
    }

    private Product createProduct(int stockQuantity) {
        return Product.builder()
                .name("테스트 상품")
                .price(10_000L)
                .stockQuantity(stockQuantity)
                .category("FOOD")
                .build();
    }
}