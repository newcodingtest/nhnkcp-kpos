package com.kcp.ordersystem.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCancellationPublisher {

    public static final String DESTINATION =
            "order-cancellation";

    private final JmsTemplate jmsTemplate;

    public void publish(Long orderId) {

        jmsTemplate.convertAndSend(
                DESTINATION,
                orderId.toString()
        );
    }
}
