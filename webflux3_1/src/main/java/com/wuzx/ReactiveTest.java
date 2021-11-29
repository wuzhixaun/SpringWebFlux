package com.wuzx;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReactiveTest {
    public static void main(String[] args) {

        Set<Integer> elements = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            elements.add(i);
        }

        final ExecutorService executorService = Executors.newFixedThreadPool(5);

        AsyncIterablePublisher<Integer> publisher = new AsyncIterablePublisher<>(elements, executorService);


        final AsyncSubscriber<Integer> subscriber = new AsyncSubscriber<>(Executors.newFixedThreadPool(2)) {
            @Override
            protected boolean whenNext(Integer element) {
                System.out.println("接收到的流元素：" + element);
                return true;
            }
        };

        publisher.subscribe(subscriber);

    }
}
