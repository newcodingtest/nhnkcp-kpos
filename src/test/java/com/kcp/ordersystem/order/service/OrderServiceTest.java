package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.common.infrastructure.memorydb.MemoryDbStore;
import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderItemRequest;
import com.kcp.ordersystem.order.api.request.OrderStatusUpdateRequest;
import com.kcp.ordersystem.order.api.response.OrderItemResponse;
import com.kcp.ordersystem.order.api.response.OrderResponse;
import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.repository.OrderRepository;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderServiceTest {

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
    @DisplayName("같은 Idempotency-Key로 주문을 다시 요청하면 기존 주문을 반환한다")
    void createOrderIdempotently() {
        Product product = saveProduct(
                "사과",
                3_000L,
                100,
                "FOOD"
        );

        OrderCreateRequest request = createRequest(product.getId(), 2);

        String idempotencyKey = "order-request-1";

        OrderResponse first =
                orderService.createV1(
                        idempotencyKey,
                        request
                );

        OrderResponse second =
                orderService.createV1(
                        idempotencyKey,
                        request
                );

        assertThat(second.id())
                .isEqualTo(first.id());

        assertThat(orderRepository.count())
                .isEqualTo(1);
    }


    @Nested
    @DisplayName("주문 조회")
    class GetOrders {
        @Test
        @DisplayName("주문 목록을 페이징하여 조회한다")
        void getOrders() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            createOrder(product.getId(), 1);
            createOrder(product.getId(), 2);
            createOrder(product.getId(), 3);

            Page<OrderResponse> result = orderService.getOrders(
                    null,
                    null,
                    null,
                    PageRequest.of(0, 2)
            );

            assertThat(result.getContent())
                    .hasSize(2);

            assertThat(result.getTotalElements())
                    .isEqualTo(3);

            assertThat(result.getTotalPages())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("주문 상태별로 조회한다")
        void getOrdersByStatus() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            Long waitingOrderId = createOrder(
                    product.getId(),
                    1
            );

            Long acceptedOrderId = createOrder(
                    product.getId(),
                    1
            );

            acceptOrder(acceptedOrderId);

            Page<OrderResponse> result = orderService.getOrders(
                    OrderStatus.ACCEPTED,
                    null,
                    null,
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent())
                    .hasSize(1);

            assertThat(result.getContent().get(0).id())
                    .isEqualTo(acceptedOrderId);

            assertThat(result.getContent().get(0).status())
                    .isEqualTo(OrderStatus.ACCEPTED);
        }

        @Test
        @DisplayName("주문을 기간별로 조회한다")
        void getOrdersByPeriod() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            Long orderId = createOrder(
                    product.getId(),
                    1
            );

            LocalDate today = LocalDate.now();

            Page<OrderResponse> result = orderService.getOrders(
                    null,
                    today,
                    today,
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent())
                    .extracting(OrderResponse::id)
                    .contains(orderId);
        }

        @Test
        @DisplayName("조회 기간에 포함되지 않는 주문은 조회되지 않는다")
        void getOrdersOutsidePeriod() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            createOrder(
                    product.getId(),
                    1
            );

            LocalDate yesterday = LocalDate.now().minusDays(1);

            Page<OrderResponse> result = orderService.getOrders(
                    null,
                    yesterday,
                    yesterday,
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent())
                    .isEmpty();

            assertThat(result.getTotalElements())
                    .isZero();
        }

        @Test
        @DisplayName("상태와 기간 조건을 함께 적용하여 주문을 조회한다")
        void getOrdersByStatusAndPeriod() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            createOrder(
                    product.getId(),
                    1
            );

            Long acceptedOrderId = createOrder(
                    product.getId(),
                    1
            );

            acceptOrder(acceptedOrderId);

            LocalDate today = LocalDate.now();

            Page<OrderResponse> result = orderService.getOrders(
                    OrderStatus.ACCEPTED,
                    today,
                    today,
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent())
                    .hasSize(1);

            assertThat(result.getContent().get(0).id())
                    .isEqualTo(acceptedOrderId);
        }

        @Test
        @DisplayName("주문 단건 조회 시 주문 상품도 함께 조회한다")
        void getOrder() {
            Product apple = saveProduct(
                    "사과",
                    3_000L,
                    100,
                    "FOOD"
            );

            Product banana = saveProduct(
                    "바나나",
                    2_000L,
                    100,
                    "FOOD"
            );

            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(
                            new OrderItemRequest(
                                    apple.getId(),
                                    2
                            ),
                            new OrderItemRequest(
                                    banana.getId(),
                                    3
                            )
                    )
            );

            Long orderId = orderService.create(request).id();

            OrderResponse response =
                    orderService.get(orderId);

            assertThat(response.id())
                    .isEqualTo(orderId);

            assertThat(response.items())
                    .hasSize(2);

            assertThat(response.items())
                    .extracting(OrderItemResponse::productName)
                    .containsExactlyInAnyOrder(
                            "사과",
                            "바나나"
                    );
        }
    }

    @Nested
    @DisplayName("재고 변경")
    class ChangeStocks {
        @Test
        @DisplayName("주문 생성 시 상태는 WAITING이고 재고는 차감되지 않는다")
        void createOrderTest() {
            Product product = saveProduct(
                    "사과",
                    3_000L,
                    10,
                    "FOOD"
            );

            OrderCreateRequest request = new OrderCreateRequest(
                    List.of(
                            new OrderItemRequest(
                                    product.getId(),
                                    3
                            )
                    )
            );

            OrderResponse response = orderService.create(request);

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            assertThat(response.status())
                    .isEqualTo(OrderStatus.WAITING);

            assertThat(response.items())
                    .hasSize(1);

            assertThat(response.items().get(0).productName())
                    .isEqualTo("사과");

            assertThat(response.items().get(0).orderPrice())
                    .isEqualTo(3_000L);

            assertThat(response.items().get(0).quantity())
                    .isEqualTo(3);

            assertThat(foundProduct.getStockQuantity())
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("주문 완료 시 상품 재고를 차감한다")
        void completeOrder() {
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

            orderService.changeStatus(
                    orderId,
                    new OrderStatusUpdateRequest(
                            OrderStatus.COMPLETED
                    )
            );

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            Order foundOrder = orderRepository
                    .findById(orderId)
                    .orElseThrow();

            assertThat(foundProduct.getStockQuantity())
                    .isEqualTo(7);

            assertThat(foundOrder.getStatus())
                    .isEqualTo(OrderStatus.COMPLETED);
        }

        @Test
        @DisplayName("완료된 주문을 취소하면 차감된 재고를 복구한다")
        void cancelCompletedOrder() {
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

            orderService.changeStatus(
                    orderId,
                    new OrderStatusUpdateRequest(
                            OrderStatus.COMPLETED
                    )
            );

            orderService.changeStatus(
                    orderId,
                    new OrderStatusUpdateRequest(
                            OrderStatus.CANCELLED
                    )
            );

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            Order foundOrder = orderRepository
                    .findById(orderId)
                    .orElseThrow();

            assertThat(foundProduct.getStockQuantity())
                    .isEqualTo(10);

            assertThat(foundOrder.getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("WAITING 주문을 취소하면 재고는 변경되지 않는다")
        void cancelWaitingOrder() {
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

            orderService.changeStatus(
                    orderId,
                    new OrderStatusUpdateRequest(
                            OrderStatus.CANCELLED
                    )
            );

            Product foundProduct = productRepository
                    .findById(product.getId())
                    .orElseThrow();

            Order foundOrder = orderRepository
                    .findById(orderId)
                    .orElseThrow();

            assertThat(foundProduct.getStockQuantity())
                    .isEqualTo(10);

            assertThat(foundOrder.getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("조회 결과가 없는 페이지에서도 전체 주문 수는 유지된다")
    void getEmptyPageWithTotalCount() {
        Product product = saveProduct(
                "사과",
                3_000L,
                100,
                "FOOD"
        );

        createOrder(product.getId(), 1);
        createOrder(product.getId(), 1);
        createOrder(product.getId(), 1);

        Page<OrderResponse> result = orderService.getOrders(
                null,
                null,
                null,
                PageRequest.of(10, 2)
        );

        assertThat(result.getContent())
                .isEmpty();

        assertThat(result.getTotalElements())
                .isEqualTo(3);
    }




    @Test
    @DisplayName("재고보다 많은 수량의 주문도 생성할 수 있지만 완료 시 실패한다")
    void failToCompleteWhenInsufficientStock() {
        Product product = saveProduct(
                "사과",
                3_000L,
                2,
                "FOOD"
        );

        Long orderId = createOrder(
                product.getId(),
                3
        );

        acceptOrder(orderId);

        assertThatThrownBy(() ->
                orderService.changeStatus(
                        orderId,
                        new OrderStatusUpdateRequest(
                                OrderStatus.COMPLETED
                        )
                )
        ).isInstanceOf(InsufficientStockException.class);

        Product foundProduct = productRepository
                .findById(product.getId())
                .orElseThrow();

        Order foundOrder = orderRepository
                .findById(orderId)
                .orElseThrow();

        assertThat(foundProduct.getStockQuantity())
                .isEqualTo(2);

        assertThat(foundOrder.getStatus())
                .isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    @DisplayName("여러 상품 중 하나의 재고가 부족하면 전체 재고 차감과 주문 상태 변경이 롤백된다")
    void rollbackWhenOneProductHasInsufficientStock() {
        Product productA = saveProduct(
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        Product productB = saveProduct(
                "바나나",
                2_000L,
                1,
                "FOOD"
        );

        OrderCreateRequest request = new OrderCreateRequest(
                List.of(
                        new OrderItemRequest(
                                productA.getId(),
                                3
                        ),
                        new OrderItemRequest(
                                productB.getId(),
                                2
                        )
                )
        );

        Long orderId = orderService.create(request).id();

        acceptOrder(orderId);

        assertThatThrownBy(() ->
                orderService.changeStatus(
                        orderId,
                        new OrderStatusUpdateRequest(
                                OrderStatus.COMPLETED
                        )
                )
        ).isInstanceOf(InsufficientStockException.class);

        Product foundProductA = productRepository
                .findById(productA.getId())
                .orElseThrow();

        Product foundProductB = productRepository
                .findById(productB.getId())
                .orElseThrow();

        Order foundOrder = orderRepository
                .findById(orderId)
                .orElseThrow();

        assertThat(foundProductA.getStockQuantity())
                .isEqualTo(10);

        assertThat(foundProductB.getStockQuantity())
                .isEqualTo(1);

        assertThat(foundOrder.getStatus())
                .isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    @DisplayName("WAITING 주문을 바로 COMPLETED 상태로 변경할 수 없다")
    void failToCompleteWaitingOrder() {
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

        assertThatThrownBy(() ->
                orderService.changeStatus(
                        orderId,
                        new OrderStatusUpdateRequest(
                                OrderStatus.COMPLETED
                        )
                )
        ).isInstanceOf(
                InvalidOrderStatusTransitionException.class
        );

        Product foundProduct = productRepository
                .findById(product.getId())
                .orElseThrow();

        Order foundOrder = orderRepository
                .findById(orderId)
                .orElseThrow();

        assertThat(foundProduct.getStockQuantity())
                .isEqualTo(10);

        assertThat(foundOrder.getStatus())
                .isEqualTo(OrderStatus.WAITING);
    }

    @Test
    @DisplayName("동일 상품이 여러 번 요청되면 수량을 합산하여 하나의 주문상품으로 생성한다")
    void aggregateDuplicatedProducts() {
        Product product = saveProduct(
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        OrderCreateRequest request = new OrderCreateRequest(
                List.of(
                        new OrderItemRequest(
                                product.getId(),
                                2
                        ),
                        new OrderItemRequest(
                                product.getId(),
                                3
                        )
                )
        );

        OrderResponse response = orderService.create(request);

        assertThat(response.items())
                .hasSize(1);

        assertThat(response.items().get(0).productId())
                .isEqualTo(product.getId());

        assertThat(response.items().get(0).quantity())
                .isEqualTo(5);
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
        OrderCreateRequest request = new OrderCreateRequest(
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

    private OrderCreateRequest createRequest(
            Long productId,
            int quantity
    ) {
        return new OrderCreateRequest(
                List.of(
                        new OrderItemRequest(
                                productId,
                                quantity
                        )
                )
        );
    }
}