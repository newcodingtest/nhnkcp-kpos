package com.kcp.ordersystem.product.repository;

import com.kcp.ordersystem.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByCategory(String category, Pageable pageable);


    @Query("""
            select p
            from Product p
            where p.id in :productIds
            """)
    List<Product> findAllByIds(
            @Param("productIds") Collection<Long> productIds
    );

    /** 주문 완료/취소에 따른 재고감소용 조회 락. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from Product p
            where p.id = :productId
            order by p.id
            """)
    Optional<Product> findByIdForUpdate(
            @Param("productId") Long productId
    );

    /** 상품재고 수정(Atomic Update) */
    @Modifying
    @Query("""
        update Product p
           set p.stockQuantity = p.stockQuantity - :quantity
         where p.id = :productId
           and p.stockQuantity >= :quantity
        """)
    int decreaseStockIfAvailable(
            @Param("productId") Long productId,
            @Param("quantity") int quantity
    );

    @Modifying
    @Query("""
        update Product p
           set p.stockQuantity =
                   p.stockQuantity + :quantity
         where p.id = :productId
        """)
    int increaseStock(
            @Param("productId") Long productId,
            @Param("quantity") int quantity
    );
}
