package com.kcp.ordersystem.order.domain;

import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    @DisplayName("주문 생성 시 상태는 WAITING이다")
    void createOrderTest() {
        Order order = createOrder();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.WAITING);
    }

    @Test
    @DisplayName("WAITING 주문을 ACCEPTED 상태로 변경한다")
    void acceptOrderTest() {
        Order order = createOrder();

        order.accept();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    @DisplayName("ACCEPTED 주문을 COMPLETED 상태로 변경한다")
    void completeOrderTest() {
        Order order = createOrder();
        order.accept();

        order.complete();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("WAITING 주문은 취소할 수 있다")
    void cancelWaitingOrderTest() {
        Order order = createOrder();

        order.cancel();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("ACCEPTED 주문은 취소할 수 있다")
    void cancelAcceptedOrderTest() {
        Order order = createOrder();
        order.accept();

        order.cancel();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("COMPLETED 주문은 취소할 수 있다")
    void cancelCompletedOrderTest() {
        Order order = createOrder();
        order.accept();
        order.complete();

        assertThat(order.isCompleted()).isTrue();

        order.cancel();

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("WAITING 주문을 바로 COMPLETED 상태로 변경할 수 없다")
    void cannotCompleteWaitingOrderTest() {
        Order order = createOrder();

        assertThatThrownBy(order::complete)
                .isInstanceOf(
                        InvalidOrderStatusTransitionException.class
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.WAITING);
    }

    @Test
    @DisplayName("COMPLETED 주문을 ACCEPTED 상태로 변경할 수 없다")
    void cannotAcceptCompletedOrderTest() {
        Order order = createOrder();
        order.accept();
        order.complete();

        assertThatThrownBy(order::accept)
                .isInstanceOf(
                        InvalidOrderStatusTransitionException.class
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("이미 취소된 주문을 다시 취소할 수 없다")
    void cannotCancelCancelledOrderTest() {
        Order order = createOrder();
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(
                        InvalidOrderStatusTransitionException.class
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    private Order createOrder() {
        OrderItem orderItem = OrderItem.builder()
                .productId(1L)
                .productName("사과")
                .orderPrice(3_000L)
                .quantity(2)
                .build();

        return Order.create(List.of(orderItem));
    }
}
