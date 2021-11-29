package com.wuzx.webflux6_1_4.entity;

public class Temperature {

    private final double value;

    public Temperature(double temperature) {
        this.value = temperature;
    }

    public double getValue() {
        return value;
    }

}
