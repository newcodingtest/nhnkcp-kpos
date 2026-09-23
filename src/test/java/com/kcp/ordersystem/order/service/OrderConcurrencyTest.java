package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderItemRequest;
import com.kcp.ordersystem.order.api.request.OrderStatusUpdateRequest;
import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.repository.OrderRepository;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("여러 주문이 같은 상품을 동시에 완료해도 재고는 음수가 되지 않는다")
    void completeOrdersConcurrently() throws Exception {
        // given
        Product product = saveProduct(
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        int threadCount = 6;

        List<Long> orderIds = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Long orderId = createOrder(
                    product.getId(),
                    2
            );

            acceptOrder(orderId);

            orderIds.add(orderId);
        }

        ExecutorService executorService =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch =
                new CountDownLatch(threadCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        try {
            List<Future<CompleteResult>> futures =
                    new ArrayList<>();

            for (Long orderId : orderIds) {
                Future<CompleteResult> future =
                        executorService.submit(() -> {
                            readyLatch.countDown();
                            startLatch.await();

                            try {
                                orderService.changeStatus(
                                        orderId,
                                        new OrderStatusUpdateRequest(
                                                OrderStatus.COMPLETED
                                        )
                                );

                                return CompleteResult.SUCCESS;
                            } catch (InsufficientStockException e) {
                                return CompleteResult.INSUFFICIENT_STOCK;
                            }
                        });

                futures.add(future);
            }

            readyLatch.await();
            startLatch.countDown();

            List<CompleteResult> results =
                    new ArrayList<>();

            for (Future<CompleteResult> future : futures) {
                results.add(future.get());
            }

            long successCount = results.stream()
                    .filter(result ->
                            result == CompleteResult.SUCCESS
                    )
                    .count();

            long failCount = results.stream()
                    .filter(result ->
                            result
                                    == CompleteResult.INSUFFICIENT_STOCK
                    )
                    .count();

            // then
            assertThat(successCount)
                    .isEqualTo(5);

            assertThat(failCount)
                    .isEqualTo(1);

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            assertThat(foundProduct.getStockQuantity())
                    .isZero();

            List<Order> orders =
                    orderRepository.findAllById(orderIds);

            long completedCount = orders.stream()
                    .filter(order ->
                            order.getStatus()
                                    == OrderStatus.COMPLETED
                    )
                    .count();

            long acceptedCount = orders.stream()
                    .filter(order ->
                            order.getStatus()
                                    == OrderStatus.ACCEPTED
                    )
                    .count();

            assertThat(completedCount)
                    .isEqualTo(5);

            assertThat(acceptedCount)
                    .isEqualTo(1);

        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 주문에 동시에 완료 요청이 들어와도 재고는 한 번만 차감된다")
    void completeSameOrderConcurrently() throws Exception {
        // given
        Product product = saveProduct(
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        Long orderId = createOrder(
                product.getId(),
                3
        );

        acceptOrder(orderId);

        int threadCount = 2;

        ExecutorService executorService =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch =
                new CountDownLatch(threadCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        try {
            List<Future<CompleteResult>> futures =
                    new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                Future<CompleteResult> future =
                        executorService.submit(() -> {
                            readyLatch.countDown();
                            startLatch.await();

                            try {
                                orderService.changeStatus(
                                        orderId,
                                        new OrderStatusUpdateRequest(
                                                OrderStatus.COMPLETED
                                        )
                                );

                                return CompleteResult.SUCCESS;

                            } catch (
                                    InvalidOrderStatusTransitionException e
                            ) {
                                return CompleteResult.INVALID_TRANSITION;
                            }
                        });

                futures.add(future);
            }

            readyLatch.await();
            startLatch.countDown();

            List<CompleteResult> results =
                    new ArrayList<>();

            for (Future<CompleteResult> future : futures) {
                results.add(future.get());
            }

            long successCount = results.stream()
                    .filter(result ->
                            result == CompleteResult.SUCCESS
                    )
                    .count();

            long invalidTransitionCount = results.stream()
                    .filter(result ->
                            result
                                    == CompleteResult.INVALID_TRANSITION
                    )
                    .count();

            // then
            assertThat(successCount)
                    .isEqualTo(1);

            assertThat(invalidTransitionCount)
                    .isEqualTo(1);

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            assertThat(foundProduct.getStockQuantity())
                    .isEqualTo(7);

            Order foundOrder = orderRepository
                    .findById(orderId)
                    .orElseThrow();

            assertThat(foundOrder.getStatus())
                    .isEqualTo(OrderStatus.COMPLETED);

        } finally {
            executorService.shutdownNow();
        }
    }

    private Product saveProduct(
            String name,
            Long price,
            int stockQuantity,
            String category
    ) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .price(price)
                        .stockQuantity(stockQuantity)
                        .category(category)
                        .build()
        );
    }

    private Long createOrder(
            Long productId,
            int quantity
    ) {
        OrderCreateRequest request =
                new OrderCreateRequest(
                        List.of(
                                new OrderItemRequest(
                                        productId,
                                        quantity
                                )
                        )
                );

        return orderService.create(request).id();
    }

    private void acceptOrder(Long orderId) {
        orderService.changeStatus(
                orderId,
                new OrderStatusUpdateRequest(
                        OrderStatus.ACCEPTED
                )
        );
    }

    private enum CompleteResult {
        SUCCESS,
        INSUFFICIENT_STOCK,
        INVALID_TRANSITION
    }
}
