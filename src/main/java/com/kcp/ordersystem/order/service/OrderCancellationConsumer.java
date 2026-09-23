package com.kcp.ordersystem.order.service;

import com.kcp.ordersystem.common.infrastructure.memorydb.MemoryDbStore;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationConsumer {

    private final OrderCommandService orderCommandService;
    private final MemoryDbStore memoryDbStore;

    @JmsListener(
            destination = OrderCancellationPublisher.DESTINATION
    )
    public void consume(String message) {

        Long orderId =
                Long.valueOf(message);

        try {

            /*
             * DB Transaction.
             *
             * Product 재고 복구
             * → COMPLETED → CANCELLED
             * → COMMIT
             */
            orderCommandService.cancelCompleted(
                    orderId
            );

            Map<Long, Integer> quantities =
                    memoryDbStore.getOrderSnapshot(
                            orderId
                    );

            /*
             * DB commit 성공 이후
             * Memory DB 재고 복구.
             *
             * orderId 기준으로 한 번만 복구한다.
             */
            memoryDbStore.restoreStocksAfterCancellation(
                    orderId,
                    quantities
            );

            memoryDbStore.updateOrderStatus(
                    orderId,
                    OrderStatus.CANCELLED
            );

            /*
             * 모든 처리 완료 후 마지막에 gate 해제.
             */
            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    OrderStatus.CANCELLED
            );

            log.info(
                    "주문 취소 처리 성공. orderId={}",
                    orderId
            );

        } catch (
                InvalidOrderStatusTransitionException
                | OrderNotFoundException
                | ProductNotFoundException e
        ) {

            /*
             * Business Failure.
             */
            memoryDbStore.finishOrderStatusChange(
                    orderId,
                    OrderStatus.CANCELLED
            );

            log.warn(
                    "주문 취소 처리 실패. orderId={}, message={}",
                    orderId,
                    e.getMessage()
            );
        }

        /*
         * Technical Exception:
         *
         * catch하지 않음
         * gate 유지
         * → MQ redelivery
         */
    }
}
