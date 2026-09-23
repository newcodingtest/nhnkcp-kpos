package com.kcp.ordersystem.order.repository;

import com.kcp.ordersystem.order.domain.Order;
import com.kcp.ordersystem.order.domain.OrderStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.kcp.ordersystem.order.domain.QOrder.order;
import static com.kcp.ordersystem.order.domain.QOrderItem.orderItem;

@RequiredArgsConstructor
public class OrderQueryRepositoryImpl
        implements OrderQueryRepository {

    private final JPAQueryFactory queryFactory;

    /** 주문 목록/상태별/기간별 조회(페이징). */
    @Override
    public Page<Order> search(
            final OrderStatus status,
            final LocalDate from,
            final LocalDate to,
            final Pageable pageable
    ) {
        //1. 최신순으로 order id 목록 추출
        List<Long> orderIds = queryFactory
                .select(order.id)
                .from(order)
                .where(
                        statusEq(status),
                        createdAtGoe(from),
                        createdAtLt(to)
                )
                .orderBy(
                        order.createdAt.desc(),
                        order.id.desc()
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        //2. 1에 해당하는 id을 기반으로 order item fetch join
        List<Order> orders = orderIds.isEmpty()
                ? List.of()
                : queryFactory
                .selectFrom(order)
                .leftJoin(order.orderItems, orderItem)
                .fetchJoin()
                .where(order.id.in(orderIds))
                .orderBy(
                        order.createdAt.desc(),
                        order.id.desc()
                )
                .fetch();


        //3. 전체 count
        Long total = queryFactory
                .select(order.count())
                .from(order)
                .where(
                        statusEq(status),
                        createdAtGoe(from),
                        createdAtLt(to)
                )
                .fetchOne();

        return new PageImpl<>(
                orders,
                pageable,
                total != null ? total : 0L
        );
    }

    private BooleanExpression statusEq(final OrderStatus status) {
        return status != null
                ? order.status.eq(status)
                : null;
    }

    private BooleanExpression createdAtGoe(final LocalDate from) {
        if (from == null) {
            return null;
        }

        return order.createdAt.goe(
                from.atStartOfDay()
        );
    }

    private BooleanExpression createdAtLt(final LocalDate to) {
        if (to == null) {
            return null;
        }

        LocalDateTime nextDay = to
                .plusDays(1)
                .atStartOfDay();

        return order.createdAt.lt(nextDay);
    }
}
