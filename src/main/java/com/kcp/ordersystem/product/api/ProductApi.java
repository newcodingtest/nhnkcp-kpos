package com.kcp.ordersystem.product.api;

import com.kcp.ordersystem.product.api.request.ProductCreateRequest;
import com.kcp.ordersystem.product.api.request.ProductUpdateRequest;
import com.kcp.ordersystem.product.api.response.ProductPageResponse;
import com.kcp.ordersystem.product.api.response.ProductResponse;
import com.kcp.ordersystem.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductApi {
    private final ProductService productService;

    /** 상품 등록. */
    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody ProductCreateRequest request
    ) {
        ProductResponse response = productService.create(request);

        return ResponseEntity
                .created(URI.create("/api/products/" + response.id()))
                .body(response);
    }

    /** 상품 수정. */
    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable Long productId,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        ProductResponse response = productService.update(productId, request);

        return ResponseEntity.ok(response);
    }

    /** 상품 조회. */
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> get(
            @PathVariable Long productId
    ) {
        ProductResponse response = productService.get(productId);

        return ResponseEntity.ok(response);
    }

    /** 카테고리의 상품들 조회.(페이징) */
    @GetMapping
    public ResponseEntity<ProductPageResponse> getProducts(
            @RequestParam(required = false) String category,
            Pageable pageable
    ) {
        Page<ProductResponse> products =
                productService.getProducts(category, pageable);

        return ResponseEntity.ok(
                ProductPageResponse.from(products)
        );
    }
}
