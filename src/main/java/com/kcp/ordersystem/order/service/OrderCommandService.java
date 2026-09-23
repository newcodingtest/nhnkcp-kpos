package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderItemRequest;
import com.kcp.ordersystem.order.api.response.OrderResponse;
import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderItem;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.order.repository.OrderRepository;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** DB Transaction이 필요한 실제 주문 담당 서비스. */
@Service
@RequiredArgsConstructor
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /** 주문 생성.
     * @param request 주문생성 요청.
     * */
    @Transactional
    public OrderResponse create(
            OrderCreateRequest request) {

        Map<Long, Integer> quantities = aggregateQuantities(request.items());

        Map<Long, Product> products = findProducts(
                quantities.keySet().stream().toList()
        );

        List<OrderItem> orderItems = quantities.entrySet()
                .stream()
                .map(entry -> {
                    Product product = products.get(entry.getKey());

                    return OrderItem.builder()
                            .productId(product.getId())
                            .productName(product.getName())
                            .orderPrice(product.getPrice())
                            .quantity(entry.getValue())
                            .build();
                })
                .toList();

        Order order = Order.create(orderItems);

        Order savedOrder = orderRepository.save(order);

        return OrderResponse.from(savedOrder);
    }

    /**
     * 재고 변경이 없는 주문 상태 변경.
     */
    @Transactional
    public OrderResponse changeWithoutStock(
            Long orderId,
            OrderStatus targetStatus
    ) {

        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException(orderId)
                        );

        switch (targetStatus) {

            case ACCEPTED ->
                    order.accept();

            case WAITING ->
                    order.validateTransitionTo(
                            OrderStatus.WAITING
                    );

            case COMPLETED, CANCELLED ->
                    throw new IllegalArgumentException(
                            "지원하지 않는 상태 변경입니다. status="
                                    + targetStatus
                    );
        }

        return OrderResponse.from(order);
    }

    /**
     * 재고 변경 없이 취소할 수 있는 주문은 즉시 취소한다.
     *
     * COMPLETED 주문은 재고 복구가 필요하므로
     * 여기서는 상태를 변경하지 않는다.
     */
    @Transactional
    public Optional<OrderResponse> cancelWithoutStockIfPossible(
            Long orderId
    ) {

        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException(orderId)
                        );

        /*
         * 먼저 Domain 상태 전이 규칙 검증.
         */
        order.validateTransitionTo(
                OrderStatus.CANCELLED
        );

        /*
         * COMPLETED → CANCELLED는
         * Product 재고 복구가 필요하므로 비동기 처리한다.
         */
        if (order.isCompleted()) {
            return Optional.empty();
        }

        /*
         * WAITING / ACCEPTED → CANCELLED
         *
         * 재고 변경이 없으므로 즉시 처리.
         */
        order.cancel();

        return Optional.of(
                OrderResponse.from(order)
        );
    }

    /**
     * COMPLETED 주문의 실제 비동기 취소 처리.
     *
     * Product 재고 복구와 Order 상태 변경을
     * 하나의 DB Transaction에서 처리한다.
     */
    @Transactional
    public void cancelCompleted(Long orderId) {

        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException(orderId)
                        );

        /*
         * MQ redelivery 대응.
         *
         * DB commit 성공 후 MQ ACK 전에 장애가 발생하면
         * 같은 메시지가 다시 전달될 수 있다.
         *
         * 이미 CANCELLED이면 재고를 다시 증가시키지 않는다.
         */
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }

        /*
         * 이 Worker는 COMPLETED → CANCELLED 전용.
         */
        if (!order.isCompleted()) {
            throw new InvalidOrderStatusTransitionException(
                    order.getStatus(),
                    OrderStatus.CANCELLED
            );
        }

        List<OrderItem> orderItems =
                order.getOrderItems()
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        OrderItem::getProductId
                                )
                        )
                        .toList();

        /*
         * Product DB 재고 복구.
         */
        for (OrderItem orderItem : orderItems) {

            int updated =
                    productRepository.increaseStock(
                            orderItem.getProductId(),
                            orderItem.getQuantity()
                    );

            if (updated != 1) {
                throw new ProductNotFoundException(
                        orderItem.getProductId()
                );
            }
        }

        /*
         * 모든 상품 재고 복구 성공 후에만
         * COMPLETED → CANCELLED.
         */
        order.cancel();
    }


    /**
     * Queue Worker가 호출하는 실제 주문 완료 처리.
     *
     * 주문 상태 변경과 DB 재고 차감을
     * 하나의 Transaction으로 처리한다.
     *
     * @param orderId 완료 처리할 주문 ID
     */
    @Transactional
    public void complete(Long orderId) {
         //1.동일 주문에 대한 Worker의 동시 처리 방지를 위하 조회락 획득
        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException(orderId)
                        );

        //2.MQ는 동일 메시지가 재전달될 수 있다
        //이미 완료된 주문이라면 재고를 다시 차감하지 않고
        //정상 처리된 요청으로 간주하여 종료한다.
        if (order.isCompleted()) {
            return;
        }

        //3.현재 주문이 ACCEPTED → COMPLETED 전이가 가능한지, 재고를 변경하기 전에 검증한다.
        order.validateTransitionTo(OrderStatus.COMPLETED);

        /*
         * 4.여러 상품을 처리할 때 항상 productId 순서대로
         * DB UPDATE를 수행한다.
         *
         * 서로 다른 주문이 동일한 여러 상품을 포함할 경우
         * Lock 획득 순서를 일정하게 하여 Deadlock 가능성을 낮춘다.
         */
        List<OrderItem> orderItems =
                order.getOrderItems()
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        OrderItem::getProductId
                                )
                        )
                        .toList();

        for (OrderItem orderItem : orderItems) {
             //5.DB에서 현재 재고가 충분한 경우에만 차감한다.
            int updated =
                    productRepository
                            .decreaseStockIfAvailable(
                                    orderItem.getProductId(),
                                    orderItem.getQuantity()
                            );

            //6.재고 부족시 모두 rollback
            if (updated != 1) {
                throw new InsufficientStockException(
                        orderItem.getProductId()
                );
            }
        }
        
        //7.모든 상품의 재고 차감이 성공
        order.complete();
    }

    /** 실제 db에 있는 상품인지 확인. */
    private Map<Long, Product> findProducts(
            List<Long> productIds
    ) {
        //1.주문 생성 시 요청된 여러 상품을 한 번에 조회
        List<Product> products =
                productRepository.findAllByIds(productIds);

        Map<Long, Product> productMap = new HashMap<>();

        for (Product product : products) {
            productMap.put(product.getId(), product);
        }

        //2.누락된 상품 ID가 있는지 검증
        for (Long productId : productIds) {
            if (!productMap.containsKey(productId)) {
                throw new ProductNotFoundException(productId);
            }
        }

        return productMap;
    }

    /**
     * {
     *   "items": [
     *     { "productId": 1, "quantity": 2 },
     *     { "productId": 2, "quantity": 1 },
     *     { "productId": 1, "quantity": 3 }
     *   ]
     * }
     * 주문 생성 요청에 동일한 productId가 여러 번 들어왔을 때 하나로 합치기 위한 메서드
     *
     * Order
     *  ├─ OrderItem(productId=1, quantity=5)
     *  └─ OrderItem(productId=2, quantity=1)
     * */
    private Map<Long, Integer> aggregateQuantities(
            List<OrderItemRequest> items
    ) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();

        for (OrderItemRequest item : items) {
            quantities.merge(
                    item.productId(),
                    item.quantity(),
                    Integer::sum
            );
        }

        return quantities;
    }
}
