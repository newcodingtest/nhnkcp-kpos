package com.kcp.ordersystem.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문상품. */
@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private Long orderPrice;

    @Column(nullable = false)
    private Integer quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Builder
    private OrderItem(
            final Long productId,
            final String productName,
            final Long orderPrice,
            final Integer quantity
    ) {
        this.productId = productId;
        this.productName = productName;
        this.orderPrice = orderPrice;
        this.quantity = quantity;
    }

    void assignOrder(Order order) {
        this.order = order;
    }
}