package com.kcp.ordersystem.product.service;

import com.kcp.ordersystem.common.infrastructure.memorydb.MemoryDbStore;
import com.kcp.ordersystem.product.api.request.ProductCreateRequest;
import com.kcp.ordersystem.product.api.request.ProductUpdateRequest;
import com.kcp.ordersystem.product.api.response.ProductResponse;
import com.kcp.ordersystem.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductCommandService productCommandService;
    private final MemoryDbStore memoryDbStore;

    public ProductResponse create(
            ProductCreateRequest request
    ) {
        /*
         * 정상 반환 시 DB Transaction commit 완료.
         */
        ProductResponse response =
                productCommandService.create(request);

        /*
         * DB commit 이후 Memory DB 재고 동기화.
         */
        memoryDbStore.syncStock(
                response.id(),
                response.stockQuantity()
        );

        return response;
    }

    public ProductResponse update(
            Long productId,
            ProductUpdateRequest request
    ) {
        ProductResponse response =
                productCommandService.update(
                        productId,
                        request
                );

        memoryDbStore.syncStock(
                response.id(),
                response.stockQuantity()
        );

        return response;
    }

    public ProductResponse get(Long productId) {

        return productCommandService.get(productId);
    }

    public Page<ProductResponse> getProducts(
            String category,
            Pageable pageable
    ) {
        return productCommandService.getProducts(category, pageable);
    }


}
