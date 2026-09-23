package com.kcp.ordersystem.product.service;

import com.kcp.ordersystem.product.api.request.ProductCreateRequest;
import com.kcp.ordersystem.product.api.request.ProductUpdateRequest;
import com.kcp.ordersystem.product.api.response.ProductResponse;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductCommandService {

    private final ProductRepository productRepository;

    /**
     * 실제 상품 등록 DB Transaction.
     */
    @Transactional
    public ProductResponse create(
            final ProductCreateRequest request
    ) {
        Product product = Product.create(
                request.name(),
                request.price(),
                request.stockQuantity(),
                request.category()
        );

        Product savedProduct =
                productRepository.save(product);

        return ProductResponse.from(savedProduct);
    }
    @Transactional
    public ProductResponse update(
            Long productId,
            ProductUpdateRequest request
    ) {
        Product product = getProduct(productId);

        product.update(
                request.name(),
                request.price(),
                request.stockQuantity(),
                request.category()
        );

        return ProductResponse.from(product);
    }

    public ProductResponse get(Long productId) {
        Product product = getProduct(productId);

        return ProductResponse.from(product);
    }

    public Page<ProductResponse> getProducts(
            String category,
            Pageable pageable
    ) {
        Page<Product> products;

        if (category == null || category.isBlank()) {
            products = productRepository.findAll(pageable);
        } else {
            products = productRepository.findByCategory(category, pageable);
        }

        return products.map(ProductResponse::from);
    }

    private Product getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
