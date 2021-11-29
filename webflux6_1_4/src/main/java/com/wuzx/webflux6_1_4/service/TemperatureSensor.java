package com.wuzx.webflux6_1_4.service;


import com.wuzx.webflux6_1_4.entity.Temperature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class TemperatureSensor {

    private final ApplicationEventPublisher publisher;
    private final Random random = new Random();
    private final ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();

    public TemperatureSensor(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostConstruct
    public void startProcessing() {
        this.service.schedule(this::probe, 1, TimeUnit.SECONDS);
    }

    private void probe() {
        double temperature = 16 + random.nextGaussian() * 10;
        System.err.println("发送事件。。。");
        // 通过ApplicationEventPublisher发布Temperature事件
        publisher.publishEvent(new Temperature(temperature));
        service.schedule(this::probe, random.nextInt(5000), TimeUnit.MILLISECONDS);
    }
}
