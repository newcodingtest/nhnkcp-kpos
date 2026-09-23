package com.kcp.ordersystem.common.infrastructure.memorydb;

import com.kcp.ordersystem.order.domain.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 실제 Redis/Valkey 같은 메모리DB를 가정
 * */
@Component
public class MemoryDbStore {

    /*
     * 실제 Redis/Valkey의 상품별 재고를 시뮬레이션한다.
     * key   : productId
     * value : Memory DB 기준 현재 예약 가능한 재고 수량
     */
    private final Map<Long, Integer> stocks = new ConcurrentHashMap<>();

    /*
     * 주문 생성 Idempotency-Key.
     * key   : Client/Gateway가 전달한 Idempotency-Key
     * value : 해당 요청의 처리 상태와 생성된 orderId
     * 예)
     * 주문 생성 처리 중:
     * idempotencyEntries = {
     *     "order-request-abc"
     *         -> IdempotencyEntry(PROCESSING, null)
     * }
     *
     * 주문 생성 완료:
     * idempotencyEntries = {
     *     "order-request-abc"
     *         -> IdempotencyEntry(COMPLETED, 100L)
     * }
     */
    private final Map<String, IdempotencyEntry> idempotencyEntries =
            new ConcurrentHashMap<>();


    /*
     * 현재 상태 변경을 처리 중인 주문과 목표 상태를 저장한다.
     *
     * key   : orderId
     * value : 현재 처리 중인 목표 OrderStatus
     */
    private final Map<Long, OrderStatus> processingOrderStatusChanges =
            new ConcurrentHashMap<>();

    /*
     * COMPLETED → CANCELLED 처리 후
     * Memory DB 재고 복구가 이미 수행된 주문.
     *
     * MQ 중복 전달 시 Memory DB 재고가
     * 두 번 증가하는 것을 방지한다.
     */
    private final Set<Long> restoredCancellationStocks =
            ConcurrentHashMap.newKeySet();

    /*
     * 주문별로 Memory DB에서 예약한 재고.
     *
     * Worker 실패 시 정확한 재고 복구를 위해 보관한다.
     *
     * key   : orderId
     * value : 해당 주문이 예약한 productId -> quantity
     *
     * Worker 성공:
     *   → 실제 DB 재고 차감 완료
     *   → 예약 정보 제거
     *
     * Worker Business Failure:
     *   → 이 정보를 기준으로 stocks 재고 복구
     *   → 예약 정보 제거
     */
    private final Map<Long, Map<Long, Integer>> stockReservations =
            new ConcurrentHashMap<>();

    /*
     * 주문 완료 처리에 필요한 상품별 주문 수량 Snapshot.
     *
     * key   : orderId
     * value : productId -> quantity
     */
    private final Map<Long, Map<Long, Integer>> orderSnapshots =
            new ConcurrentHashMap<>();

    /*
     * Memory DB에서 관리하는 주문 상태.
     *
     * key   : orderId
     * value : 현재 알려진 주문 상태
     */
    private final Map<Long, OrderStatus> orderStatuses =
            new ConcurrentHashMap<>();

    /*
     * 실제 Redis/Valkey의 Lua Script가 원자적으로 실행되는 상황을
     * 단일 JVM에서 시뮬레이션하기 위한 lock.
     */
    private final ReentrantLock stockLock = new ReentrantLock();


    // ========================================================
    // Idempotency
    // ========================================================

    public boolean tryStartRequest(final String idempotencyKey) {
        return idempotencyEntries.putIfAbsent(
                idempotencyKey,
                IdempotencyEntry.processing()
        ) == null;
    }

    public Optional<IdempotencyEntry> getIdempotencyEntry(
            final String idempotencyKey
    ) {
        return Optional.ofNullable(
                idempotencyEntries.get(idempotencyKey)
        );
    }

    public void completeRequest(
            final String idempotencyKey,
            final Long orderId
    ) {
        idempotencyEntries.compute(
                idempotencyKey,
                (key, entry) -> {
                    if (entry == null) {
                        throw new IllegalStateException(
                                "등록되지 않은 멱등키입니다. key=" + key
                        );
                    }

                    return IdempotencyEntry.completed(orderId);
                }
        );
    }

    public void removeRequest(final String idempotencyKey) {
        idempotencyEntries.remove(idempotencyKey);
    }


    /**
     * 주문 상태 변경 처리 권한을 획득한다.
     *
     * 같은 orderId가 이미 처리 중이라면 false를 반환한다.
     */
    public boolean tryStartOrderStatusChange(
            final Long orderId,
            final OrderStatus targetStatus
    ) {
        if (orderId == null) {
            throw new IllegalArgumentException(
                    "주문 ID는 null일 수 없습니다."
            );
        }

        if (targetStatus == null) {
            throw new IllegalArgumentException(
                    "목표 주문 상태는 null일 수 없습니다."
            );
        }

        return processingOrderStatusChanges.putIfAbsent(
                orderId,
                targetStatus
        ) == null;
    }

    public Optional<OrderStatus> getProcessingOrderStatus(
            final Long orderId
    ) {
        return Optional.ofNullable(
                processingOrderStatusChanges.get(orderId)
        );
    }

    /**
     * 주문 상태 변경 처리 종료.
     *
     */
    public void finishOrderStatusChange(
            final Long orderId,
            final OrderStatus targetStatus
    ) {
        processingOrderStatusChanges.remove(
                orderId,
                targetStatus
        );
    }


    // ========================================================
    // Stock
    // ========================================================

    /**
     * Memory DB에 상품 재고가 없는 경우에만
     * DB 재고를 초기값으로 등록한다.
     */
    public void initializeStockIfAbsent(
            final Long productId,
            final int quantity
    ) {
        if (quantity < 0) {
            throw new IllegalArgumentException(
                    "재고는 0 이상이어야 합니다."
            );
        }

        stocks.putIfAbsent(
                productId,
                quantity
        );
    }

    public int getStock(final Long productId) {
        Integer stock = stocks.get(productId);

        if (stock == null) {
            throw new IllegalStateException(
                    "Memory DB에 재고가 존재하지 않습니다. productId="
                            + productId
            );
        }

        return stock;
    }

    /**
     * COMPLETED 주문 취소 성공 후
     * Memory DB 재고를 한 번만 복구한다.
     */
    public void restoreStocksAfterCancellation(
            final Long orderId,
            final Map<Long, Integer> quantities
    ) {
        validateQuantities(quantities);

        stockLock.lock();

        try {
            /*
             * MQ 중복 전달.
             *
             * 이미 Memory DB 재고를 복구했다면 no-op.
             */
            if (restoredCancellationStocks.contains(
                    orderId
            )) {
                return;
            }

            /*
             * 변경 전에 모든 상품이 존재하는지 검증.
             *
             * 중간까지 복구되고 실패하는 상태를 방지한다.
             */
            for (Long productId : quantities.keySet()) {
                getRequiredStock(productId);
            }

            quantities.forEach(
                    (productId, quantity) -> {

                        int currentStock =
                                getRequiredStock(
                                        productId
                                );

                        stocks.put(
                                productId,
                                currentStock + quantity
                        );
                    }
            );

            restoredCancellationStocks.add(
                    orderId
            );

        } finally {
            stockLock.unlock();
        }
    }

    /**
     * 여러 상품의 재고를 원자적으로 예약한다.
     *
     * 모든 상품의 재고가 충분할 때만 전체 차감한다.
     */
    public boolean reserveStocks(
            final Long orderId,
            final Map<Long, Integer> quantities
    ) {
        validateQuantities(quantities);

        stockLock.lock();

        try {
            /*
             * 동일 주문이 이미 예약되어 있다면
             * 다시 재고를 차감하지 않는다.
             */
            if (stockReservations.containsKey(orderId)) {
                return true;
            }

            for (Map.Entry<Long, Integer> entry
                    : quantities.entrySet()) {

                int currentStock =
                        getRequiredStock(entry.getKey());

                if (currentStock < entry.getValue()) {
                    return false;
                }
            }

            /*
             * 모든 상품이 충분한 것을 확인한 뒤
             * 전체 재고를 차감한다.
             */
            quantities.forEach(
                    (productId, quantity) -> {
                        int currentStock =
                                getRequiredStock(productId);

                        stocks.put(
                                productId,
                                currentStock - quantity
                        );
                    }
            );

            /*
             * 보상 처리를 위해 예약 정보를 저장한다.
             */
            stockReservations.put(
                    orderId,
                    Map.copyOf(quantities)
            );

            return true;

        } finally {
            stockLock.unlock();
        }
    }

    /**
     * Worker 처리 성공.
     *
     * Memory DB에서는 이미 재고를 차감했으므로
     * 예약 정보만 제거한다.
     */
    public void completeReservation(final Long orderId) {
        stockLock.lock();

        try {
            stockReservations.remove(orderId);

        } finally {
            stockLock.unlock();
        }
    }

    /**
     * Worker 처리 실패.
     *
     * 해당 주문이 예약했던 재고를 복구한다.
     */
    public void releaseReservation(final Long orderId) {
        stockLock.lock();

        try {
            Map<Long, Integer> reservation =
                    stockReservations.remove(orderId);

            /*
             * 이미 복구된 요청이라면 아무것도 하지 않는다.
             * 따라서 보상 처리도 멱등성을 가진다.
             */
            if (reservation == null) {
                return;
            }

            reservation.forEach(
                    (productId, quantity) -> {
                        int currentStock =
                                getRequiredStock(productId);

                        stocks.put(
                                productId,
                                currentStock + quantity
                        );
                    }
            );

        } finally {
            stockLock.unlock();
        }
    }

    public void updateOrderStatus(
            final Long orderId,
            final OrderStatus status
    ) {
        if (orderId == null) {
            throw new IllegalArgumentException(
                    "주문 ID는 null일 수 없습니다."
            );
        }

        if (status == null) {
            throw new IllegalArgumentException(
                    "주문 상태는 null일 수 없습니다."
            );
        }

        orderStatuses.put(
                orderId,
                status
        );
    }

    public OrderStatus getOrderStatus(
            final Long orderId
    ) {
        OrderStatus status =
                orderStatuses.get(orderId);

        if (status == null) {
            throw new IllegalStateException(
                    "Memory DB에 주문 상태가 존재하지 않습니다. orderId="
                            + orderId
            );
        }

        return status;
    }

    private int getRequiredStock(final Long productId) {
        Integer stock = stocks.get(productId);

        if (stock == null) {
            throw new IllegalStateException(
                    "Memory DB에 상품 재고가 존재하지 않습니다. productId="
                            + productId
            );
        }

        return stock;
    }

    private void validateQuantities(
            final Map<Long, Integer> quantities
    ) {
        if (quantities == null || quantities.isEmpty()) {
            throw new IllegalArgumentException(
                    "상품 수량은 비어 있을 수 없습니다."
            );
        }

        quantities.forEach(
                (productId, quantity) -> {
                    if (productId == null) {
                        throw new IllegalArgumentException(
                                "상품 ID는 null일 수 없습니다."
                        );
                    }

                    if (quantity == null || quantity <= 0) {
                        throw new IllegalArgumentException(
                                "상품 수량은 1 이상이어야 합니다."
                        );
                    }
                }
        );
    }

    /**
     * 상품 DB 재고 변경 결과를 Memory DB에 동기화한다.
     *
     * 상품 등록/직접 재고 수정처럼 DB의 재고값 자체가
     * 변경된 경우 사용한다.
     *
     * 주문 완료의 재고 예약 과정에서는 사용하지 않는다.
     */
    public void syncStock(
            final Long productId,
            final int stockQuantity
    ) {
        if (productId == null) {
            throw new IllegalArgumentException(
                    "상품 ID는 null일 수 없습니다."
            );
        }

        if (stockQuantity < 0) {
            throw new IllegalArgumentException(
                    "재고는 0 이상이어야 합니다."
            );
        }

        stocks.put(
                productId,
                stockQuantity
        );
    }

    public void clear() {
        stockLock.lock();

        try {
            stocks.clear();
            idempotencyEntries.clear();

            processingOrderStatusChanges.clear();

            stockReservations.clear();
            orderSnapshots.clear();
            orderStatuses.clear();

            restoredCancellationStocks.clear();
        } finally {
            stockLock.unlock();
        }
    }

    /**
     * 주문 생성이 DB에 정상 반영된 이후
     * 완료 처리에 필요한 주문상품 정보를 Memory DB에 저장한다.
     */
    public void saveOrderSnapshot(
            final Long orderId,
            final Map<Long, Integer> quantities
    ) {
        if (orderId == null) {
            throw new IllegalArgumentException(
                    "주문 ID는 null일 수 없습니다."
            );
        }

        validateQuantities(quantities);

        /*
         * 외부에서 Map을 수정하더라도
         * 저장된 Snapshot이 변경되지 않도록 복사한다.
         */
        orderSnapshots.put(
                orderId,
                Map.copyOf(quantities)
        );
    }

    /**
     * 주문 완료 처리용 Snapshot 조회.
     */
    public Map<Long, Integer> getOrderSnapshot(
            final Long orderId
    ) {
        Map<Long, Integer> snapshot =
                orderSnapshots.get(orderId);

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Memory DB에 주문 Snapshot이 존재하지 않습니다. orderId="
                            + orderId
            );
        }

        return snapshot;
    }
}
