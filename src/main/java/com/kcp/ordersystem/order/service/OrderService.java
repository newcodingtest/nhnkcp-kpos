package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.common.infrastructure.memorydb.IdempotencyEntry;
import com.kcp.ordersystem.common.infrastructure.memorydb.MemoryDbStore;
import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderItemRequest;
import com.kcp.ordersystem.order.api.request.OrderStatusUpdateRequest;
import com.kcp.ordersystem.order.api.response.OrderResponse;
import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderItem;
import com.kcp.ordersystem.order.domain.OrderStatus;

import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.order.exception.OrderRequestInProgressException;
import com.kcp.ordersystem.order.exception.OrderStatusChangeInProgressException;
import com.kcp.ordersystem.order.repository.OrderRepository;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    private final OrderCommandService orderCommandService;

    //valkey/redis
    private final MemoryDbStore memoryDbStore;

    private final OrderCompletionPublisher orderCompletionPublisher;

    private final OrderCancellationPublisher orderCancellationPublisher;

    /** 주문 단건 조회. */
    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId) {
        Order order = orderRepository
                .findByIdWithItems(orderId)
                .orElseThrow(
                        () -> new OrderNotFoundException(orderId)
                );

        return OrderResponse.from(order);
    }
    /** 주문 목록/상태별/기간별 조회. */
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(
            OrderStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable
    ) {
        return orderRepository
                .search(
                        status,
                        from,
                        to,
                        pageable
                )
                .map(OrderResponse::from);
    }

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
     * 재고 변경이 없는 일반 주문 상태 변경.
     */
    public OrderResponse changeStatus(
            Long orderId,
            OrderStatusUpdateRequest request
    ) {

        OrderStatus targetStatus =
                request.status();

        boolean acquired =
                memoryDbStore.tryStartOrderStatusChange(
                        orderId,
                        targetStatus
                );

        if (!acquired) {

            OrderStatus processingStatus =
                    memoryDbStore
                            .getProcessingOrderStatus(orderId)
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "주문 처리 상태를 확인할 수 없습니다."
                                    )
                            );

            throw new OrderStatusChangeInProgressException(
                    orderId,
                    processingStatus
            );
        }

        try {

            OrderResponse response =
                    orderCommandService.changeWithoutStock(
                            orderId,
                            targetStatus
                    );

            /*
             * DB commit 성공 후 Memory DB 상태 동기화.
             */
            memoryDbStore.updateOrderStatus(
                    orderId,
                    targetStatus
            );

            return response;

        } finally {

            /*
             * 동기 처리이므로 호출 종료 시 바로 gate 해제.
             */
            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    targetStatus
            );
        }
    }

    /**
     * 주문 취소.
     *
     * WAITING / ACCEPTED
     * → 동기 취소
     *
     * COMPLETED
     * → 비동기 취소
     */
    public Optional<OrderResponse> cancel(
            Long orderId
    ) {

        /*
         * 완료/취소가 같은 주문에서 동시에 진행되지 않도록
         * CANCELLED 상태 변경 gate를 먼저 획득한다.
         */
        boolean acquired =
                memoryDbStore.tryStartOrderStatusChange(
                        orderId,
                        OrderStatus.CANCELLED
                );

        if (!acquired) {

            OrderStatus processingStatus =
                    memoryDbStore
                            .getProcessingOrderStatus(orderId)
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "주문 처리 상태를 확인할 수 없습니다."
                                    )
                            );

            /*
             * 같은 취소 요청이 이미 처리 중이라면
             * 중복 처리하지 않는다.
             *
             * Optional.empty()는 현재 API에서
             * 202 Accepted 응답으로 연결된다.
             */
            if (processingStatus == OrderStatus.CANCELLED) {
                return Optional.empty();
            }

            /*
             * 예:
             * COMPLETED 처리가 진행 중인데 CANCELLED 요청.
             *
             * 취소 요청을 실제로 접수한 것이 아니므로
             * Conflict로 응답한다.
             */
            throw new OrderStatusChangeInProgressException(
                    orderId,
                    processingStatus
            );
        }

        boolean async = false;

        try {

            /*
             * Order row를 Lock한 상태에서:
             *
             * WAITING / ACCEPTED
             * → 즉시 CANCELLED
             *
             * COMPLETED
             * → Optional.empty()
             */
            Optional<OrderResponse> response =
                    orderCommandService
                            .cancelWithoutStockIfPossible(
                                    orderId
                            );

            /*
             * WAITING / ACCEPTED → CANCELLED
             */
            if (response.isPresent()) {

                memoryDbStore.updateOrderStatus(
                        orderId,
                        OrderStatus.CANCELLED
                );

                return response;
            }

            /*
             * COMPLETED → CANCELLED.
             *
             * DB 재고 복구가 필요하므로 MQ 처리.
             */
            orderCancellationPublisher.publish(
                    orderId
            );

            /*
             * Consumer가 gate를 해제해야 하므로
             * 현재 Service에서는 유지한다.
             */
            async = true;

            return Optional.empty();

        } finally {

            /*
             * 동기 취소이거나 MQ 발행 전에 실패한 경우
             * 여기서 gate를 해제한다.
             *
             * MQ 발행에 성공한 비동기 취소는
             * Consumer가 해제한다.
             */
            if (!async) {
                memoryDbStore.finishOrderStatusChange(
                        orderId,
                        OrderStatus.CANCELLED
                );
            }
        }
    }

    /** 주문 생성.
     * @param idempotencyKey 주문생성 멱등키.
     * @param request 주문생성 요청.
     *
     *
     *[순서]
     * MemoryDb = PROCESSING
     *         ↓
     * OrderCommandService @Transactional
     *         ↓
     * DB INSERT
     *         ↓
     * DB COMMIT
     *         ↓
     * MemoryDb = COMPLETED(orderId)
     * */

    public OrderResponse createV1(
            String idempotencyKey,
            OrderCreateRequest request) {

        //1.주문 생성 멱등키 기반 검사
        IdempotencyEntry existing =
                memoryDbStore
                        .getIdempotencyEntry(idempotencyKey)
                        .orElse(null);

        if (existing != null) {
            return handleExistingRequest(existing);
        }

        //2.동일 Idempotency-Key에 대한 처리 권한을 원자적으로 획득
        boolean acquired =
                memoryDbStore.tryStartRequest(idempotencyKey);

        //3.다른 Thread가 동일 키에 대한 권한 사용시 exception(동일한 주문 요청이 처리)
        if (!acquired) {
            IdempotencyEntry current =
                    memoryDbStore
                            .getIdempotencyEntry(idempotencyKey)
                            .orElseThrow(
                                    OrderRequestInProgressException::new
                            );

            return handleExistingRequest(current);
        }

        /*
         * 주문 완료 요청 시 DB를 다시 조회하지 않기 위해
         * 상품별 주문 수량을 미리 계산해둔다.
         */
        Map<Long, Integer> quantities =
                aggregateQuantities(request.items());

        try {
            /*
             * 실제 DB Transaction.
             */
            OrderResponse response =
                    orderCommandService.create(request);

            /*
             * DB commit 이후 Memory DB에 주문 Snapshot 저장.
             */
            memoryDbStore.saveOrderSnapshot(
                    response.id(),
                    quantities
            );

            /*
             * Memory DB에서도 주문 상태 관리.
             */
            memoryDbStore.updateOrderStatus(
                    response.id(),
                    OrderStatus.WAITING
            );

            memoryDbStore.completeRequest(
                    idempotencyKey,
                    response.id()
            );

            return response;

        } catch (RuntimeException e) {

            memoryDbStore.removeRequest(
                    idempotencyKey
            );

            throw e;
        }
    }

    /**
     * 주문 완료 비동기 요청.
     *
     * Memory DB 재고 예약
     * → MQ 발행
     * → Consumer
     * → DB Transaction
     */
    public void requestCompletion(Long orderId) {

        /*
         * 주문 생성 시 저장한 주문상품 Snapshot.
         */
        Map<Long, Integer> quantities =
                memoryDbStore.getOrderSnapshot(
                        orderId
                );

        /*
         * 같은 주문에서 다른 상태 변경이 동시에
         * 진행되지 않도록 공통 gate를 획득한다.
         */
        boolean acquired =
                memoryDbStore.tryStartOrderStatusChange(
                        orderId,
                        OrderStatus.COMPLETED
                );

        if (!acquired) {

            OrderStatus processingStatus =
                    memoryDbStore
                            .getProcessingOrderStatus(orderId)
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "주문 처리 상태를 확인할 수 없습니다."
                                    )
                            );

            /*
             * 같은 COMPLETED 요청의 중복 호출이라면
             * 이미 처리 중인 요청으로 보고 멱등하게 종료한다.
             */
            if (processingStatus == OrderStatus.COMPLETED) {
                return;
            }

            /*
             * CANCELLED 등 다른 상태 변경이 진행 중이면
             * 현재 요청을 접수하지 않는다.
             */
            throw new OrderStatusChangeInProgressException(
                    orderId,
                    processingStatus
            );
        }

        boolean reserved = false;

        try {

            /*
             * Memory DB 재고 선점.
             */
            reserved =
                    memoryDbStore.reserveStocks(
                            orderId,
                            quantities
                    );

            if (!reserved) {
                throw new InsufficientStockException();
            }

            /*
             * Memory DB 예약 성공 요청만 MQ에 전달한다.
             */
            orderCompletionPublisher.publish(
                    orderId
            );

        } catch (RuntimeException e) {

            /*
             * MQ 발행 전후 실패 시
             * 예약했던 Memory DB 재고 복구.
             */
            if (reserved) {
                memoryDbStore.releaseReservation(
                        orderId
                );
            }

            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    OrderStatus.COMPLETED
            );

            throw e;
        }

        /*
         * MQ 발행 성공 시 gate는 여기서 해제하지 않는다.
         *
         * Consumer에서 실제 DB 처리까지 끝난 뒤 해제한다.
         */
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
                                () -> new OrderNotFoundException(
                                        orderId
                                )
                        );

        switch (targetStatus) {

            case ACCEPTED ->
                    order.accept();

            case WAITING ->
                    order.validateTransitionTo(
                            OrderStatus.WAITING
                    );

            /*
             * COMPLETED / CANCELLED는 각각
             * 별도의 처리 경로가 존재한다.
             */
            case COMPLETED, CANCELLED ->
                    throw new IllegalArgumentException(
                            "재고 변경 없는 상태 변경 대상이 아닙니다. status="
                                    + targetStatus
                    );
        }

        return OrderResponse.from(order);
    }

    /**
     * 주문 상품을 productId별 필요 수량으로 집계한다.
     */
    private Map<Long, Integer> createQuantities(
            Order order
    ) {
        Map<Long, Integer> quantities =
                new HashMap<>();

        for (OrderItem orderItem : order.getOrderItems()) {
            quantities.merge(
                    orderItem.getProductId(),
                    orderItem.getQuantity(),
                    Integer::sum
            );
        }

        return quantities;
    }

    /**
     * Memory DB에 등록되지 않은 상품 재고를
     * DB의 현재 재고 값으로 초기화한다.
     *
     * 실제 운영 환경에서는 외부 Memory DB의 재고가
     * 별도의 동기화 과정으로 관리된다고 가정한다.
     * 과제에서는 별도 인프라 구성을 하지 않기 때문에
     * 최초 완료 요청 시 DB 값을 기준으로 초기화한다.
     */
    private void initializeStocksIfAbsent(
            Map<Long, Integer> quantities
    ) {
        List<Long> productIds =
                quantities.keySet()
                        .stream()
                        .sorted()
                        .toList();

        /*
         * 주문 상품마다 개별 조회하지 않고
         * 한 번의 조회로 필요한 상품을 가져온다.
         */
        List<Product> products =
                productRepository.findAllByIds(productIds);

        Map<Long, Product> productMap =
                new HashMap<>();

        for (Product product : products) {
            productMap.put(
                    product.getId(),
                    product
            );
        }

        /*
         * 요청한 상품 중 존재하지 않는 상품이 있는지 검증한다.
         */
        for (Long productId : productIds) {
            Product product =
                    productMap.get(productId);

            if (product == null) {
                throw new ProductNotFoundException(productId);
            }

            /*
             * Memory DB에 이미 재고가 존재하면 덮어쓰지 않는다.
             *
             * 이미 다른 주문에서 재고를 예약한 상태일 수 있기 때문에
             * DB 재고로 다시 덮어쓰면 안 된다.
             */
            memoryDbStore.initializeStockIfAbsent(
                    productId,
                    product.getStockQuantity()
            );
        }
    }



    private void complete(Order order) {
        //1. 주문완료 가능한지 먼저 검증
        order.validateTransitionTo(OrderStatus.COMPLETED);

        //2. 주문완료 상태로 변경되면 Product Lock
        Map<Long, Product> products =
                findProductsWithLock(order);

        //3. 주문 상품에 대한 재고 차감
        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = products.get(
                    orderItem.getProductId()
            );

            product.decreaseStock(
                    orderItem.getQuantity()
            );
        }

        //4. 모든 처리가 성공하면 COMPLETE 상태 변경
        order.complete();
    }

    private void cancel(Order order) {
        //1. 취소 가능한 주문인지 먼저 검증
        order.validateTransitionTo(OrderStatus.CANCELLED);

        //2. 기존 상태가 COMPLETED라면 Product Lock
        if (order.isCompleted()) {
            Map<Long, Product> products =
                    findProductsWithLock(order);
            //3. 재고 복구
            for (OrderItem orderItem : order.getOrderItems()) {
                Product product = products.get(
                        orderItem.getProductId()
                );

                product.increaseStock(
                        orderItem.getQuantity()
                );
            }
        }
        //4. 모든 처리가 성공하면 CANCELLED로 상태 변경
        order.cancel();
    }

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

    private Map<Long, Product> findProductsWithLock(
            Order order
    ) {
        List<Long> productIds = order.getOrderItems()
                .stream()
                .map(OrderItem::getProductId)
                .distinct()
                .sorted()
                .toList();

        Map<Long, Product> products = new HashMap<>();

        for (Long productId : productIds) {
            Product product = productRepository
                    .findByIdForUpdate(productId)
                    .orElseThrow(
                            () -> new ProductNotFoundException(productId)
                    );

            products.put(productId, product);
        }

        return products;
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

    /**
     * 중복주문 요청에 대한 반환 값
     * */
    private OrderResponse handleExistingRequest(
            IdempotencyEntry entry
    ) {
        if (entry.status() == IdempotencyEntry.Status.PROCESSING) {
            throw new OrderRequestInProgressException();
        }

        Order order =
                orderRepository.findById(entry.orderId())
                        .orElseThrow(
                                () -> new OrderNotFoundException(
                                        entry.orderId()
                                )
                        );

        return OrderResponse.from(order);
    }
}
