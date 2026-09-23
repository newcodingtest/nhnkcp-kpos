package com.kcp.ordersystem.order.domain;

import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 주문. */
@Getter
@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(
                        name = "idx_orders_created_at",
                        columnList = "created_at"
                ),
                @Index(
                        name = "idx_orders_status_created_at",
                        columnList = "status, created_at"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItem> orderItems = new ArrayList<>();

    private Order(List<OrderItem> orderItems) {
        this.status = OrderStatus.WAITING;
        this.createdAt = LocalDateTime.now();

        orderItems.forEach(this::addOrderItem);
    }

    public static Order create(List<OrderItem> orderItems) {
        return new Order(orderItems);
    }

    public void accept() {
        transitionTo(OrderStatus.ACCEPTED);
    }

    public void complete() {
        transitionTo(OrderStatus.COMPLETED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    /**
     * WAITING
     *  ├─> ACCEPTED
     *  └─> CANCELLED
     *
     * ACCEPTED
     *  ├─> COMPLETED
     *  └─> CANCELLED
     *
     * COMPLETED
     *  └─> CANCELLED
     *
     * CANCELLED
     *  └─> 변경 불가
     * */
    public void validateTransitionTo(OrderStatus targetStatus) {
        boolean allowed = switch (status) {
            case WAITING ->
                    targetStatus == OrderStatus.ACCEPTED
                            || targetStatus == OrderStatus.CANCELLED;

            case ACCEPTED ->
                    targetStatus == OrderStatus.COMPLETED
                            || targetStatus == OrderStatus.CANCELLED;

            case COMPLETED ->
                    targetStatus == OrderStatus.CANCELLED;

            case CANCELLED -> false;
        };

        if (!allowed) {
            throw new InvalidOrderStatusTransitionException(
                    status,
                    targetStatus
            );
        }
    }

    private void transitionTo(OrderStatus targetStatus) {
        validateTransitionTo(targetStatus);
        this.status = targetStatus;
    }

    public boolean isCompleted() {
        return status == OrderStatus.COMPLETED;
    }

    private void addOrderItem(OrderItem orderItem) {
        orderItems.add(orderItem);
        orderItem.assignOrder(this);
    }
}
