package com.kcp.ordersystem.order.repository;

import com.kcp.ordersystem.order.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderQueryRepository {

    /** 동시 주문 방지용 조회 락. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o
            from Order o
            where o.id = :orderId
            """)
    Optional<Order> findByIdForUpdate(
            @Param("orderId") Long orderId
    );

    /** 주문 단건 조회. */
    @Query("""
            select o
            from Order o
            left join fetch o.orderItems
            where o.id = :orderId
            """)
    Optional<Order> findByIdWithItems(
            @Param("orderId") Long orderId
    );


}
