package com.kcp.ordersystem.product.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProductUpdateRequest(

        @NotBlank(message = "상품명은 필수입니다.")
        String name,

        @NotNull(message = "가격은 필수입니다.")
        @PositiveOrZero(message = "가격은 0 이상이어야 합니다.")
        Long price,

        @NotNull(message = "재고수량은 필수입니다.")
        @PositiveOrZero(message = "재고수량은 0 이상이어야 합니다.")
        Integer stockQuantity,

        @NotBlank(message = "카테고리는 필수입니다.")
        String category
) {
}
