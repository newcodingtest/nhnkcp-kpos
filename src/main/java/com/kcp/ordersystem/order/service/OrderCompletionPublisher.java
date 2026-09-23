package com.kcp.ordersystem.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCompletionPublisher {

    public static final String DESTINATION = "order-completion";

    private final JmsTemplate jmsTemplate;

    /**
     * 주문 완료 요청 발행.
     *
     * @param orderId 완료 처리할 주문 ID
     */
    public void publish(Long orderId) {
        jmsTemplate.convertAndSend(
                DESTINATION,
                orderId.toString()
        );
    }
}
