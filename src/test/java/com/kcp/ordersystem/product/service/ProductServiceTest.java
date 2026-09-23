package com.kcp.ordersystem.product.service;

import com.kcp.ordersystem.product.api.request.ProductCreateRequest;
import com.kcp.ordersystem.product.api.request.ProductUpdateRequest;
import com.kcp.ordersystem.product.api.response.ProductResponse;
import com.kcp.ordersystem.product.domain.Product;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import com.kcp.ordersystem.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("상품을 등록한다")
    void createProduct() {
        ProductCreateRequest request = new ProductCreateRequest(
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        ProductResponse response = productService.create(request);

        Product product = productRepository.findById(response.id())
                .orElseThrow();

        assertThat(product.getName()).isEqualTo("사과");
        assertThat(product.getPrice()).isEqualTo(3_000L);
        assertThat(product.getStockQuantity()).isEqualTo(10);
        assertThat(product.getCategory()).isEqualTo("FOOD");
    }

    @Test
    @DisplayName("상품을 수정하면 Dirty Checking으로 변경사항이 반영된다")
    void updateProduct() {
        Product product = saveProduct("사과", 3_000L, 10, "FOOD");

        ProductUpdateRequest request = new ProductUpdateRequest(
                "청사과",
                4_000L,
                20,
                "FOOD"
        );

        productService.update(product.getId(), request);

        Product updatedProduct = productRepository.findById(product.getId())
                .orElseThrow();

        assertThat(updatedProduct.getName()).isEqualTo("청사과");
        assertThat(updatedProduct.getPrice()).isEqualTo(4_000L);
        assertThat(updatedProduct.getStockQuantity()).isEqualTo(20);
        assertThat(updatedProduct.getCategory()).isEqualTo("FOOD");
    }

    @Nested
    @DisplayName("상품 조회")
    class GetProducts {
        @Test
        @DisplayName("상품을 단건 조회한다")
        void getProduct() {
            Product product = saveProduct("사과", 3_000L, 10, "FOOD");

            ProductResponse response = productService.get(product.getId());

            assertThat(response.id()).isEqualTo(product.getId());
            assertThat(response.name()).isEqualTo("사과");
            assertThat(response.price()).isEqualTo(3_000L);
            assertThat(response.stockQuantity()).isEqualTo(10);
            assertThat(response.category()).isEqualTo("FOOD");
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면 예외가 발생한다")
        void getProductNotFound() {
            assertThatThrownBy(() -> productService.get(999L))
                    .isInstanceOf(ProductNotFoundException.class);
        }

        @Test
        @DisplayName("상품 목록을 페이징하여 조회한다")
        void getProducts() {
            saveProduct("사과", 3_000L, 10, "FOOD");
            saveProduct("노트북", 1_000_000L, 5, "ELECTRONICS");

            Page<ProductResponse> result = productService.getProducts(
                    null,
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("카테고리별 상품 목록을 조회한다")
        void getProductsByCategory() {
            saveProduct("사과", 3_000L, 10, "FOOD");
            saveProduct("바나나", 2_000L, 20, "FOOD");
            saveProduct("노트북", 1_000_000L, 5, "ELECTRONICS");

            Page<ProductResponse> result = productService.getProducts(
                    "FOOD",
                    PageRequest.of(0, 10)
            );

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent())
                    .extracting(ProductResponse::category)
                    .containsOnly("FOOD");
        }
    }

    private Product saveProduct(
            String name,
            Long price,
            int stockQuantity,
            String category
    ) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .price(price)
                        .stockQuantity(stockQuantity)
                        .category(category)
                        .build()
        );
    }
}