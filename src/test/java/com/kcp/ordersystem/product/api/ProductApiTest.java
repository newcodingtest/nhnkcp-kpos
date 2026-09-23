package com.kcp.ordersystem.product.api;

import com.kcp.ordersystem.common.exception.GlobalExceptionHandler;
import com.kcp.ordersystem.product.api.response.ProductResponse;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import com.kcp.ordersystem.product.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductApi.class)
public class ProductApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    @DisplayName("상품을 등록한다")
    void createProduct() throws Exception {
        ProductResponse response = new ProductResponse(
                1L,
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        given(productService.create(any()))
                .willReturn(response);

        mockMvc.perform(
                        post("/api/products")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "사과",
                                          "price": 3000,
                                          "stockQuantity": 10,
                                          "category": "FOOD"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/products/1"
                ))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("사과"))
                .andExpect(jsonPath("$.price").value(3000))
                .andExpect(jsonPath("$.stockQuantity").value(10))
                .andExpect(jsonPath("$.category").value("FOOD"));
    }

    @Test
    @DisplayName("잘못된 상품 등록 요청은 400을 반환한다")
    void createProductInvalidRequest() throws Exception {
        mockMvc.perform(
                        post("/api/products")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "",
                                          "price": -1000,
                                          "stockQuantity": -1,
                                          "category": ""
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .isNotEmpty());
    }

    @Test
    @DisplayName("상품을 수정한다")
    void updateProduct() throws Exception {
        ProductResponse response = new ProductResponse(
                1L,
                "청사과",
                4_000L,
                20,
                "FOOD"
        );

        given(productService.update(eq(1L), any()))
                .willReturn(response);

        mockMvc.perform(
                        put("/api/products/{productId}", 1L)
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "청사과",
                                          "price": 4000,
                                          "stockQuantity": 20,
                                          "category": "FOOD"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("청사과"))
                .andExpect(jsonPath("$.price").value(4000))
                .andExpect(jsonPath("$.stockQuantity").value(20));
    }

    @Test
    @DisplayName("상품을 단건 조회한다")
    void getProduct() throws Exception {
        ProductResponse response = new ProductResponse(
                1L,
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        given(productService.get(1L))
                .willReturn(response);

        mockMvc.perform(
                        get("/api/products/{productId}", 1L)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("사과"))
                .andExpect(jsonPath("$.category").value("FOOD"));
    }

    @Test
    @DisplayName("존재하지 않는 상품 조회 시 404를 반환한다")
    void getProductNotFound() throws Exception {
        given(productService.get(999L))
                .willThrow(new ProductNotFoundException(999L));

        mockMvc.perform(
                        get("/api/products/{productId}", 999L)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .isNotEmpty());
    }

    @Test
    @DisplayName("카테고리별 상품 목록을 페이징 조회한다")
    void getProductsByCategory() throws Exception {
        ProductResponse apple = new ProductResponse(
                1L,
                "사과",
                3_000L,
                10,
                "FOOD"
        );

        ProductResponse banana = new ProductResponse(
                2L,
                "바나나",
                2_000L,
                20,
                "FOOD"
        );

        PageRequest pageable = PageRequest.of(0, 20);

        given(productService.getProducts(
                eq("FOOD"),
                eq(pageable)
        )).willReturn(
                new PageImpl<>(
                        List.of(apple, banana),
                        pageable,
                        2
                )
        );

        mockMvc.perform(
                        get("/api/products")
                                .param("category", "FOOD")
                                .param("page", "0")
                                .param("size", "20")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()")
                        .value(2))
                .andExpect(jsonPath("$.content[0].category")
                        .value("FOOD"))
                .andExpect(jsonPath("$.content[1].category")
                        .value("FOOD"))
                .andExpect(jsonPath("$.page")
                        .value(0))
                .andExpect(jsonPath("$.size")
                        .value(20))
                .andExpect(jsonPath("$.totalElements")
                        .value(2))
                .andExpect(jsonPath("$.totalPages")
                        .value(1));
    }
}
