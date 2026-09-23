package com.kcp.ordersystem.common.infrastructure.memorydb;

import com.kcp.ordersystem.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryDbStoreTest {

    private MemoryDbStore memoryDbStore;

    @BeforeEach
    void setUp() {
        memoryDbStore = new MemoryDbStore();
    }

    // ========================================================
    // Stock
    // ========================================================

    @Test
    @DisplayName("상품 재고를 Memory DB에 동기화한다")
    void syncStock() {

        // when
        memoryDbStore.syncStock(
                1L,
                10
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("재고 동기화 시 기존 재고를 새로운 값으로 변경한다")
    void syncStockOverwrite() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        // when
        memoryDbStore.syncStock(
                1L,
                20
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(20);
    }

    @Test
    @DisplayName("재고 동기화 시 음수 재고는 허용하지 않는다")
    void syncStockWithNegativeQuantity() {

        assertThatThrownBy(
                () -> memoryDbStore.syncStock(
                        1L,
                        -1
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고는 0 이상이어야 합니다.");
    }

    @Test
    @DisplayName("Memory DB에 재고가 없으면 초기 재고를 등록한다")
    void initializeStockIfAbsent() {

        // when
        memoryDbStore.initializeStockIfAbsent(
                1L,
                10
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("Memory DB에 이미 재고가 있으면 초기 재고로 덮어쓰지 않는다")
    void initializeStockDoesNotOverwriteExistingStock() {

        // given
        memoryDbStore.syncStock(
                1L,
                8
        );

        // when
        memoryDbStore.initializeStockIfAbsent(
                1L,
                10
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(8);
    }

    @Test
    @DisplayName("존재하지 않는 상품의 Memory DB 재고를 조회하면 실패한다")
    void getStockNotFound() {

        assertThatThrownBy(
                () -> memoryDbStore.getStock(1L)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("productId=1");
    }


    // ========================================================
    // Stock Reservation
    // ========================================================

    @Test
    @DisplayName("여러 상품의 재고를 한 번에 예약한다")
    void reserveStocks() {

        // given
        memoryDbStore.syncStock(1L, 10);
        memoryDbStore.syncStock(2L, 20);

        Map<Long, Integer> quantities =
                Map.of(
                        1L, 2,
                        2L, 3
                );

        // when
        boolean reserved =
                memoryDbStore.reserveStocks(
                        100L,
                        quantities
                );

        // then
        assertThat(reserved).isTrue();

        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(8);

        assertThat(
                memoryDbStore.getStock(2L)
        ).isEqualTo(17);
    }

    @Test
    @DisplayName("여러 상품 중 하나라도 재고가 부족하면 모든 상품의 재고를 차감하지 않는다")
    void reserveStocksAtomicallyWhenStockIsInsufficient() {

        // given
        memoryDbStore.syncStock(1L, 10);
        memoryDbStore.syncStock(2L, 1);

        Map<Long, Integer> quantities =
                Map.of(
                        1L, 2,
                        2L, 2
                );

        // when
        boolean reserved =
                memoryDbStore.reserveStocks(
                        100L,
                        quantities
                );

        // then
        assertThat(reserved).isFalse();

        /*
         * Product 1은 충분했더라도
         * Product 2가 부족했으므로 아무것도 차감되면 안 된다.
         */
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);

        assertThat(
                memoryDbStore.getStock(2L)
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 주문의 재고 예약 요청은 재고를 중복 차감하지 않는다")
    void reserveStocksIdempotently() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        Map<Long, Integer> quantities =
                Map.of(
                        1L,
                        2
                );

        // when
        boolean first =
                memoryDbStore.reserveStocks(
                        100L,
                        quantities
                );

        boolean second =
                memoryDbStore.reserveStocks(
                        100L,
                        quantities
                );

        // then
        assertThat(first).isTrue();
        assertThat(second).isTrue();

        /*
         * 10 → 8까지만 차감되어야 한다.
         *
         * 10 → 8 → 6이 되면 안 된다.
         */
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(8);
    }

    @Test
    @DisplayName("예약된 재고를 복구한다")
    void releaseReservation() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        memoryDbStore.reserveStocks(
                100L,
                Map.of(1L, 2)
        );

        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(8);

        // when
        memoryDbStore.releaseReservation(
                100L
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 예약을 두 번 복구해도 재고는 한 번만 복구된다")
    void releaseReservationIdempotently() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        memoryDbStore.reserveStocks(
                100L,
                Map.of(1L, 2)
        );

        // when
        memoryDbStore.releaseReservation(100L);
        memoryDbStore.releaseReservation(100L);

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("예약 완료 처리 후에는 release를 호출해도 재고가 복구되지 않는다")
    void completeReservation() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        memoryDbStore.reserveStocks(
                100L,
                Map.of(1L, 2)
        );

        // when
        memoryDbStore.completeReservation(
                100L
        );

        memoryDbStore.releaseReservation(
                100L
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(8);
    }


    // ========================================================
    // Cancellation Stock Restore
    // ========================================================

    @Test
    @DisplayName("완료된 주문 취소 시 Memory DB 재고를 복구한다")
    void restoreStocksAfterCancellation() {

        // given
        memoryDbStore.syncStock(
                1L,
                8
        );

        // when
        memoryDbStore.restoreStocksAfterCancellation(
                100L,
                Map.of(1L, 2)
        );

        // then
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("동일 취소 메시지가 중복 처리되어도 Memory DB 재고는 한 번만 복구된다")
    void restoreStocksAfterCancellationIdempotently() {

        // given
        memoryDbStore.syncStock(
                1L,
                8
        );

        Map<Long, Integer> quantities =
                Map.of(
                        1L,
                        2
                );

        // when
        memoryDbStore.restoreStocksAfterCancellation(
                100L,
                quantities
        );

        memoryDbStore.restoreStocksAfterCancellation(
                100L,
                quantities
        );

        // then
        /*
         * 8 → 10까지만 증가해야 한다.
         *
         * 8 → 10 → 12가 되면 안 된다.
         */
        assertThat(
                memoryDbStore.getStock(1L)
        ).isEqualTo(10);
    }


    // ========================================================
    // Order Snapshot
    // ========================================================

    @Test
    @DisplayName("주문 완료 처리에 필요한 상품 수량 Snapshot을 저장한다")
    void saveOrderSnapshot() {

        // given
        Map<Long, Integer> quantities =
                Map.of(
                        1L, 2,
                        2L, 3
                );

        // when
        memoryDbStore.saveOrderSnapshot(
                100L,
                quantities
        );

        // then
        assertThat(
                memoryDbStore.getOrderSnapshot(100L)
        )
                .containsEntry(1L, 2)
                .containsEntry(2L, 3);
    }

    @Test
    @DisplayName("주문 Snapshot은 외부 Map 변경의 영향을 받지 않는다")
    void saveOrderSnapshotDefensiveCopy() {

        // given
        Map<Long, Integer> quantities =
                new HashMap<>();

        quantities.put(
                1L,
                2
        );

        memoryDbStore.saveOrderSnapshot(
                100L,
                quantities
        );

        // when
        quantities.put(
                1L,
                100
        );

        // then
        assertThat(
                memoryDbStore
                        .getOrderSnapshot(100L)
                        .get(1L)
        ).isEqualTo(2);
    }

    @Test
    @DisplayName("존재하지 않는 주문 Snapshot 조회는 실패한다")
    void getOrderSnapshotNotFound() {

        assertThatThrownBy(
                () -> memoryDbStore.getOrderSnapshot(
                        100L
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orderId=100");
    }


    // ========================================================
    // Idempotency
    // ========================================================

    @Test
    @DisplayName("동일 Idempotency-Key는 하나의 요청만 처리 권한을 획득한다")
    void tryStartRequest() {

        // given
        String key = "order-request-1";

        // when
        boolean first =
                memoryDbStore.tryStartRequest(key);

        boolean second =
                memoryDbStore.tryStartRequest(key);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("주문 생성 완료 시 Idempotency 상태를 COMPLETED로 변경한다")
    void completeRequest() {

        // given
        String key = "order-request-1";

        memoryDbStore.tryStartRequest(
                key
        );

        // when
        memoryDbStore.completeRequest(
                key,
                100L
        );

        // then
        IdempotencyEntry entry =
                memoryDbStore
                        .getIdempotencyEntry(key)
                        .orElseThrow();

        assertThat(entry.status())
                .isEqualTo(
                        IdempotencyEntry.Status.COMPLETED
                );

        assertThat(entry.orderId())
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("등록되지 않은 Idempotency-Key를 완료 처리하면 실패한다")
    void completeUnknownRequest() {

        assertThatThrownBy(
                () -> memoryDbStore.completeRequest(
                        "unknown",
                        100L
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("Idempotency-Key를 제거하면 동일 키로 다시 처리 권한을 획득할 수 있다")
    void removeRequest() {

        // given
        String key = "order-request-1";

        memoryDbStore.tryStartRequest(key);

        // when
        memoryDbStore.removeRequest(key);

        // then
        assertThat(
                memoryDbStore.tryStartRequest(key)
        ).isTrue();
    }


    // ========================================================
    // Order Status Change Gate
    // ========================================================

    @Test
    @DisplayName("동일 주문은 동시에 서로 다른 상태 변경을 시작할 수 없다")
    void onlyOneOrderStatusChangeCanProceed() {

        // given
        Long orderId = 100L;

        // when
        boolean completion =
                memoryDbStore
                        .tryStartOrderStatusChange(
                                orderId,
                                OrderStatus.COMPLETED
                        );

        boolean cancellation =
                memoryDbStore
                        .tryStartOrderStatusChange(
                                orderId,
                                OrderStatus.CANCELLED
                        );

        // then
        assertThat(completion).isTrue();
        assertThat(cancellation).isFalse();

        assertThat(
                memoryDbStore
                        .getProcessingOrderStatus(orderId)
        ).contains(
                OrderStatus.COMPLETED
        );
    }

    @Test
    @DisplayName("상태 변경 gate가 해제되면 다음 상태 변경을 시작할 수 있다")
    void startNextStatusChangeAfterFinish() {

        // given
        Long orderId = 100L;

        memoryDbStore.tryStartOrderStatusChange(
                orderId,
                OrderStatus.COMPLETED
        );

        // when
        memoryDbStore.finishOrderStatusChange(
                orderId,
                OrderStatus.COMPLETED
        );

        // then
        assertThat(
                memoryDbStore.tryStartOrderStatusChange(
                        orderId,
                        OrderStatus.CANCELLED
                )
        ).isTrue();
    }

    @Test
    @DisplayName("다른 목표 상태로 gate 해제를 시도하면 기존 gate는 제거되지 않는다")
    void finishOrderStatusChangeWithDifferentTarget() {

        // given
        Long orderId = 100L;

        memoryDbStore.tryStartOrderStatusChange(
                orderId,
                OrderStatus.COMPLETED
        );

        // when
        memoryDbStore.finishOrderStatusChange(
                orderId,
                OrderStatus.CANCELLED
        );

        // then
        assertThat(
                memoryDbStore
                        .getProcessingOrderStatus(orderId)
        ).contains(
                OrderStatus.COMPLETED
        );
    }


    // ========================================================
    // Order Status
    // ========================================================

    @Test
    @DisplayName("Memory DB의 주문 상태를 저장하고 조회한다")
    void updateOrderStatus() {

        // when
        memoryDbStore.updateOrderStatus(
                100L,
                OrderStatus.ACCEPTED
        );

        // then
        assertThat(
                memoryDbStore.getOrderStatus(100L)
        ).isEqualTo(
                OrderStatus.ACCEPTED
        );
    }

    @Test
    @DisplayName("존재하지 않는 주문의 상태 조회는 실패한다")
    void getOrderStatusNotFound() {

        assertThatThrownBy(
                () -> memoryDbStore.getOrderStatus(
                        100L
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orderId=100");
    }


    // ========================================================
    // Concurrency
    // ========================================================

    @Test
    @DisplayName("동시에 재고를 예약해도 가용 재고보다 많이 예약할 수 없다")
    void reserveStocksConcurrently() throws Exception {

        // given
        Long productId = 1L;

        memoryDbStore.syncStock(
                productId,
                10
        );

        int threadCount = 20;

        ExecutorService executorService =
                Executors.newFixedThreadPool(
                        threadCount
                );

        CountDownLatch ready =
                new CountDownLatch(
                        threadCount
                );

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch done =
                new CountDownLatch(
                        threadCount
                );

        AtomicInteger successCount =
                new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {

            final long orderId = i + 1L;

            executorService.submit(
                    () -> {
                        try {
                            ready.countDown();

                            start.await();

                            boolean reserved =
                                    memoryDbStore.reserveStocks(
                                            orderId,
                                            Map.of(
                                                    productId,
                                                    1
                                            )
                                    );

                            if (reserved) {
                                successCount.incrementAndGet();
                            }

                        } catch (InterruptedException e) {
                            Thread.currentThread()
                                    .interrupt();

                        } finally {
                            done.countDown();
                        }
                    }
            );
        }

        assertThat(
                ready.await(
                        3,
                        TimeUnit.SECONDS
                )
        ).isTrue();

        start.countDown();

        assertThat(
                done.await(
                        5,
                        TimeUnit.SECONDS
                )
        ).isTrue();

        executorService.shutdownNow();

        // then
        assertThat(
                successCount.get()
        ).isEqualTo(10);

        assertThat(
                memoryDbStore.getStock(productId)
        ).isZero();
    }


    // ========================================================
    // Clear
    // ========================================================

    @Test
    @DisplayName("Memory DB 데이터를 모두 초기화한다")
    void clear() {

        // given
        memoryDbStore.syncStock(
                1L,
                10
        );

        memoryDbStore.saveOrderSnapshot(
                100L,
                Map.of(1L, 2)
        );

        memoryDbStore.updateOrderStatus(
                100L,
                OrderStatus.ACCEPTED
        );

        memoryDbStore.tryStartRequest(
                "order-request-1"
        );

        memoryDbStore.tryStartOrderStatusChange(
                100L,
                OrderStatus.COMPLETED
        );

        // when
        memoryDbStore.clear();

        // then
        assertThatThrownBy(
                () -> memoryDbStore.getStock(1L)
        ).isInstanceOf(
                IllegalStateException.class
        );

        assertThatThrownBy(
                () -> memoryDbStore.getOrderSnapshot(100L)
        ).isInstanceOf(
                IllegalStateException.class
        );

        assertThatThrownBy(
                () -> memoryDbStore.getOrderStatus(100L)
        ).isInstanceOf(
                IllegalStateException.class
        );

        assertThat(
                memoryDbStore
                        .getIdempotencyEntry(
                                "order-request-1"
                        )
        ).isEmpty();

        assertThat(
                memoryDbStore
                        .getProcessingOrderStatus(100L)
        ).isEmpty();
    }
}