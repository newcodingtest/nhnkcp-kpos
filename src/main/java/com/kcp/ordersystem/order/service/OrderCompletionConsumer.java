package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.common.infrastructure.memorydb.MemoryDbStore;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * 주문 완료 요청 Consumer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompletionConsumer {

    private final OrderCommandService orderCommandService;
    private final MemoryDbStore memoryDbStore;

    @JmsListener(
            destination = OrderCompletionPublisher.DESTINATION
    )
    public void consume(String message) {

        Long orderId =
                Long.valueOf(message);

        try {

            /*
             * 실제 DB Transaction.
             *
             * Order Lock
             * → 상태 검증
             * → Product 재고 차감
             * → COMPLETED
             * → COMMIT
             */
            orderCommandService.complete(
                    orderId
            );

            /*
             * DB commit 성공 후
             * Memory DB 예약을 확정한다.
             */
            memoryDbStore.completeReservation(
                    orderId
            );

            memoryDbStore.updateOrderStatus(
                    orderId,
                    OrderStatus.COMPLETED
            );

            /*
             * DB와 Memory DB 정리가 끝난 후에야
             * 공통 상태변경 gate를 해제한다.
             */
            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    OrderStatus.COMPLETED
            );

            log.info(
                    "주문 완료 처리 성공. orderId={}",
                    orderId
            );

        } catch (
                InsufficientStockException
                | InvalidOrderStatusTransitionException
                | OrderNotFoundException e
        ) {

            /*
             * Business Failure.
             */
            memoryDbStore.releaseReservation(
                    orderId
            );

            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    OrderStatus.COMPLETED
            );

            log.warn(
                    "주문 완료 처리 실패. orderId={}, message={}",
                    orderId,
                    e.getMessage()
            );
        }

        /*
         * DB Connection 장애 등의 Technical Exception은
         * catch하지 않는다.
         *
         * gate와 reservation도 유지한다.
         *
         * → JMS rollback
         * → MQ redelivery
         */
    }
}