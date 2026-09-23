package com.kcp.ordersystem.common.exception;

import com.kcp.ordersystem.order.exception.InvalidOrderStatusTransitionException;
import com.kcp.ordersystem.order.exception.OrderNotFoundException;
import com.kcp.ordersystem.order.exception.OrderStatusChangeInProgressException;
import com.kcp.ordersystem.product.exception.InsufficientStockException;
import com.kcp.ordersystem.product.exception.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 미존재 상품 exception. */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(
            ProductNotFoundException exception
    ) {
        ErrorResponse response = new ErrorResponse(
                "PRODUCT_NOT_FOUND",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }
    /** 재고부족 exception. */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(
            InsufficientStockException exception
    ) {
        ErrorResponse response = new ErrorResponse(
                "INSUFFICIENT_STOCK",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    /** 미존재 주문 exception. */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(
            OrderNotFoundException exception
    ) {
        ErrorResponse response = new ErrorResponse(
                "ORDER_NOT_FOUND",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    /** 주문 상태 전이 실패 exception. */
    @ExceptionHandler(
            InvalidOrderStatusTransitionException.class
    )
    public ResponseEntity<ErrorResponse>
    handleInvalidOrderStatusTransition(
            InvalidOrderStatusTransitionException exception
    ) {
        ErrorResponse response = new ErrorResponse(
                "INVALID_ORDER_STATUS_TRANSITION",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    /** api 파라미터 valid 검증 exception. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        FieldError fieldError = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        String message = fieldError != null
                ? fieldError.getDefaultMessage()
                : "잘못된 요청입니다.";

        ErrorResponse response = new ErrorResponse(
                "INVALID_REQUEST",
                message
        );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    /** api 파라미터 타입 검증 exception. */
    @ExceptionHandler(
            MethodArgumentTypeMismatchException.class
    )
    public ResponseEntity<ErrorResponse>
    handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        ErrorResponse response = new ErrorResponse(
                "INVALID_REQUEST",
                "잘못된 요청입니다."
        );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    /** 상태 변경이 진행 중 exception. */
    @ExceptionHandler(OrderStatusChangeInProgressException.class)
    public ResponseEntity<ErrorResponse> handleOrderStatusChangeInProgress(
            OrderStatusChangeInProgressException e
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(
                        new ErrorResponse(
                                "ORDER_STATUS_CHANGE_IN_PROGRESS",
                                e.getMessage()
                        )
                );
    }
}
