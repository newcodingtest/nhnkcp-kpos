package com.kcp.ordersystem.order.repository;

import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface OrderQueryRepository {

    /** 주문 목록/상태별/기간별 조회(페이징). */
    Page<Order> search(
            OrderStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable
    );
}
