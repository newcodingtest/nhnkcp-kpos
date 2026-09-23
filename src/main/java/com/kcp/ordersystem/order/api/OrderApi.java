package com.kcp.ordersystem.order.api;

import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderStatusUpdateRequest;
import com.kcp.ordersystem.order.api.response.AsyncOrderResponse;
import com.kcp.ordersystem.order.api.response.OrderPageResponse;
import com.kcp.ordersystem.order.api.response.OrderResponse;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderApi {
    private final OrderService orderService;

    /** 주문 생성 api. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) final String idempotencyKey,
            @Valid @RequestBody final OrderCreateRequest request
    ) {
        String requestKey = resolveIdempotencyKey(idempotencyKey);

        OrderResponse response =
                orderService.createV1(requestKey, request);

        return ResponseEntity
                .created(
                        URI.create(
                                "/api/orders/" + response.id()
                        )
                )
                .body(response);
    }

    /** 주문 상태 변경 api. */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<?> changeStatus(
            @PathVariable final Long orderId,
            @Valid @RequestBody final OrderStatusUpdateRequest request
    ) {

        /*
         * ACCEPTED → COMPLETED
         *
         * 재고 변경이 발생하므로 비동기 처리.
         */
        if (request.status() == OrderStatus.COMPLETED) {

            orderService.requestCompletion(orderId);

            return ResponseEntity
                    .accepted()
                    .body(
                            AsyncOrderResponse.accepted(
                                    orderId,
                                    "주문 완료 요청이 접수되었습니다. 처리 중입니다."
                            )
                    );
        }

        /*
         * CANCELLED는 현재 주문 상태에 따라
         * 동기 또는 비동기로 처리된다.
         */
        if (request.status() == OrderStatus.CANCELLED) {

            Optional<OrderResponse> response =
                    orderService.cancel(orderId);

            /*
             * COMPLETED → CANCELLED
             *
             * 재고 복구가 필요하므로 비동기 처리.
             */
            if (response.isEmpty()) {
                return ResponseEntity
                        .accepted()
                        .body(
                                AsyncOrderResponse.accepted(
                                        orderId,
                                        "주문 취소 요청이 접수되었습니다. 처리 중입니다."
                                )
                        );
            }

            /*
             * WAITING / ACCEPTED → CANCELLED
             *
             * 재고 변경이 없으므로 동기 처리 완료.
             */
            return ResponseEntity.ok(
                    response.get()
            );
        }

        /*
         * WAITING → ACCEPTED 등의 일반 상태 변경.
         */
        return ResponseEntity.ok(
                orderService.changeStatus(
                        orderId,
                        request
                )
        );
    }


    /** 주문 조회 api. */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(
            @PathVariable final Long orderId
    ) {
        return ResponseEntity.ok(
                orderService.get(orderId)
        );
    }

    /** 주문 목록/상태별/기간별 조회 api. */
    @GetMapping
    public ResponseEntity<OrderPageResponse> getOrders(
            @RequestParam(required = false)
            OrderStatus status,

            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE
            )
            LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE
            )
            LocalDate to,

            Pageable pageable
    ) {
        Page<OrderResponse> orders =
                orderService.getOrders(
                        status,
                        from,
                        to,
                        pageable
                );

        return ResponseEntity.ok(
                OrderPageResponse.from(orders)
        );
    }

    /**
     * 외부에서 Idempotency-Key가 만들어짐을 가정.
     * 없으면 서버가 임시 생성하는 멱등키 메서드.
     * */
    private String resolveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }

        return UUID.randomUUID().toString();
    }
}
