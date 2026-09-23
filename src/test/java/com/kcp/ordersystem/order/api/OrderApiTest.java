package com.kcp.ordersystem.order.api;

import com.kcp.ordersystem.order.api.request.OrderCreateRequest;
import com.kcp.ordersystem.order.api.request.OrderStatusUpdateRequest;
import com.kcp.ordersystem.order.api.response.OrderItemResponse;
import com.kcp.ordersystem.order.api.response.OrderResponse;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.order.service.OrderService;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderApi.class)
public class OrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Nested
    @DisplayName("주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("주문을 생성하면 201 Created를 반환한다")
        void createOrder() throws Exception {
            OrderResponse response = orderResponse(
                    1L,
                    OrderStatus.WAITING
            );

            given(orderService.create(any(OrderCreateRequest.class)))
                    .willReturn(response);

            mockMvc.perform(
                            post("/api/orders")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "items": [
                                                {
                                                  "productId": 1,
                                                  "quantity": 2
                                                }
                                              ]
                                            }
                                            """)
                    )
                    .andExpect(status().isCreated())
                    .andExpect(header().string(
                            "Location",
                            "/api/orders/1"
                    ))
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("WAITING"))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].productId").value(1))
                    .andExpect(jsonPath("$.items[0].quantity").value(2));
        }

        @Test
        @DisplayName("주문상품이 없으면 400을 반환한다")
        void createOrderWithoutItems() throws Exception {
            mockMvc.perform(
                            post("/api/orders")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "items": []
                                            }
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("주문수량이 0 이하이면 400을 반환한다")
        void createOrderWithInvalidQuantity() throws Exception {
            mockMvc.perform(
                            post("/api/orders")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "items": [
                                                {
                                                  "productId": 1,
                                                  "quantity": 0
                                                }
                                              ]
                                            }
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("주문 상태 변경")
    class ChangeStatus {

        @Test
        @DisplayName("주문 상태를 변경한다")
        void changeStatus() throws Exception {
            OrderResponse response = orderResponse(
                    1L,
                    OrderStatus.ACCEPTED
            );

            given(orderService.changeStatus(
                    eq(1L),
                    any(OrderStatusUpdateRequest.class)
            )).willReturn(response);

            mockMvc.perform(
                            patch("/api/orders/{orderId}/status", 1L)
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "status": "ACCEPTED"
                                            }
                                            """)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status")
                            .value("ACCEPTED"));
        }

        @Test
        @DisplayName("허용되지 않은 상태 변경은 409를 반환한다")
        void invalidStatusTransition() throws Exception {
            given(orderService.changeStatus(
                    eq(1L),
                    any(OrderStatusUpdateRequest.class)
            )).willThrow(
                    new InvalidOrderStatusTransitionException(
                            OrderStatus.WAITING,
                            OrderStatus.COMPLETED
                    )
            );

            mockMvc.perform(
                            patch("/api/orders/{orderId}/status", 1L)
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "status": "COMPLETED"
                                            }
                                            """)
                    )
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_ORDER_STATUS_TRANSITION"));
        }

        @Test
        @DisplayName("재고가 부족하면 409를 반환한다")
        void insufficientStock() throws Exception {
            given(orderService.changeStatus(
                    eq(1L),
                    any(OrderStatusUpdateRequest.class)
            )).willThrow(new InsufficientStockException());

            mockMvc.perform(
                            patch("/api/orders/{orderId}/status", 1L)
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "status": "COMPLETED"
                                            }
                                            """)
                    )
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("INSUFFICIENT_STOCK"));
        }
    }

    @Nested
    @DisplayName("주문 조회")
    class GetOrder {

        @Test
        @DisplayName("주문을 단건 조회한다")
        void getOrder() throws Exception {
            given(orderService.get(1L))
                    .willReturn(
                            orderResponse(
                                    1L,
                                    OrderStatus.WAITING
                            )
                    );

            mockMvc.perform(
                            get("/api/orders/{orderId}", 1L)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status")
                            .value("WAITING"))
                    .andExpect(jsonPath("$.items.length()")
                            .value(1));
        }

        @Test
        @DisplayName("존재하지 않는 주문 조회 시 404를 반환한다")
        void getOrderNotFound() throws Exception {
            given(orderService.get(999L))
                    .willThrow(
                            new OrderNotFoundException(999L)
                    );

            mockMvc.perform(
                            get("/api/orders/{orderId}", 999L)
                    )
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value("ORDER_NOT_FOUND"));
        }

        @Test
        @DisplayName("상태와 기간 조건으로 주문 목록을 조회한다")
        void getOrders() throws Exception {
            LocalDate from = LocalDate.of(2026, 9, 1);
            LocalDate to = LocalDate.of(2026, 9, 30);

            PageRequest pageable =
                    PageRequest.of(0, 20);

            OrderResponse response = orderResponse(
                    1L,
                    OrderStatus.COMPLETED
            );

            given(orderService.getOrders(
                    eq(OrderStatus.COMPLETED),
                    eq(from),
                    eq(to),
                    eq(pageable)
            )).willReturn(
                    new PageImpl<>(
                            List.of(response),
                            pageable,
                            1
                    )
            );

            mockMvc.perform(
                            get("/api/orders")
                                    .param(
                                            "status",
                                            "COMPLETED"
                                    )
                                    .param(
                                            "from",
                                            "2026-09-01"
                                    )
                                    .param(
                                            "to",
                                            "2026-09-30"
                                    )
                                    .param("page", "0")
                                    .param("size", "20")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()")
                            .value(1))
                    .andExpect(jsonPath("$.content[0].status")
                            .value("COMPLETED"))
                    .andExpect(jsonPath("$.page")
                            .value(0))
                    .andExpect(jsonPath("$.size")
                            .value(20))
                    .andExpect(jsonPath("$.totalElements")
                            .value(1))
                    .andExpect(jsonPath("$.totalPages")
                            .value(1));
        }

        @Test
        @DisplayName("존재하지 않는 주문 상태 조회 조건은 400을 반환한다")
        void getOrdersWithInvalidStatus() throws Exception {
            mockMvc.perform(
                            get("/api/orders")
                                    .param("status", "UNKNOWN")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("잘못된 날짜 형식은 400을 반환한다")
        void getOrdersWithInvalidDate() throws Exception {
            mockMvc.perform(
                            get("/api/orders")
                                    .param("from", "invalid-date")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_REQUEST"));
        }
    }

    private OrderResponse orderResponse(
            Long id,
            OrderStatus status
    ) {
        return new OrderResponse(
                id,
                status,
                LocalDateTime.of(
                        2026,
                        9,
                        21,
                        12,
                        0
                ),
                List.of(
                        new OrderItemResponse(
                                1L,
                                "사과",
                                3_000L,
                                2
                        )
                )
        );
    }
}
