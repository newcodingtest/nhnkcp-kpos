package com.kcp.ordersystem.order.domain;

public enum OrderStatus {

    /** 대기. */
    WAITING,

    /** 접수. */
    ACCEPTED,

    /** 완료. */
    COMPLETED,

    /** 취소. */
    CANCELLED
}