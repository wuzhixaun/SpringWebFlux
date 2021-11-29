package com.wuzx;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Publisher的实现，使用指定的Executor异步执行，并为指定的Iterable生成元素。
 * 以unicast的形式为Subscriber指定的Iterable生成元素。
 *
 * @param <T>
 */
public class AsyncIterablePublisher<T> implements Publisher<T> {

    // 默认的批次大小
    private final static int DEFAULT_BATCHSIZE = 1024;

    // 用于生成数据的数据源或生成器
    private final Iterable<T> elements;

    // 线程池，Publisher使用线程池为它的订阅者异步执行
    private final Executor executor;

    /**
     * 既然使用了线程池，就不要在一个线程中执行太多的任务
     * 此处使用批次大小调节单个线程的执行时长
     */
    private final int batchSize;


    /***
     * @param elements 元素生成器
     * @param executor 线程池
     * */
    public AsyncIterablePublisher(final Iterable<T> elements, final Executor executor) {
        // 调用重载的构造器，使用默认的批次大小，指定的数据源和指定的线程池
        this(elements, DEFAULT_BATCHSIZE, executor);
    }


    /***
     * 构造器，构造Publisher实例
     * @param elements 元素发生器
     * @param batchSize 批次大小
     * @param executor 线程池
     * */
    public AsyncIterablePublisher(final Iterable<T> elements, final int batchSize, final Executor executor) {
        // 如果不指定元素发生器则抛异常
        if (elements == null) {
            throw null;
        }
        // 如果不指定线程池，抛异常
        if (executor == null) {
            throw null;
        }
        // 如果批次大小小于1抛异常：批次大小必须是大于等于1的值
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be greater than zero!");
        }
        // 赋值元素发生器
        this.elements = elements;
        // 赋值线程池
        this.executor = executor;
        // 赋值批次大小
        this.batchSize = batchSize;
    }


    /**
     * 订阅者订阅的方法
     * 规范2.13指出，该方法必须正常返回，不能抛异常等。。。
     * 在当前实现中，我们使用unicast的方式支持多个订阅者
     *
     * @param subscriber 订阅者
     */
    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        new SubscriptionImpl(subscriber).init();
    }

    /**
     * 静态接口：信号
     */
    static interface Signal {
    }

    /**
     * 取消订阅的信号
     */
    enum Cancel implements Signal {Instance;}

    /**
     * 订阅的信号
     */
    enum Subscribe implements Signal {Instance;}

    /**
     * 发送的信号
     */
    enum Send implements Signal {Instance;}

    /**
     * 静态类，表示请求信号
     */
    static final class Request implements Signal {
        final long n;

        Request(final long n) {
            this.n = n;
        }
    }


    /**
     * 订阅票据，实现了Subscription接口和Runnable接口
     */
    final class SubscriptionImpl implements Subscription, Runnable {
        /**
         * 需要保有Subscriber的引用，以用于通信
         */
        final Subscriber<? super T> subscriber;

        // 需要发送给订阅者（Subscriber）的数据流指针
        private Iterator<T> iterator;

        /**
         * 该队列记录发送给票据的信号（入站信号），如"request"，"cancel"等
         * 通过该Queue，可以在Publisher端使用多线程异步处理。
         */
        private final ConcurrentLinkedQueue<Signal> inboundSignals = new ConcurrentLinkedQueue<Signal>();


        // 确保当前票据不会并发的标志
        // 防止在调用订阅者的onXxx方法的时候并发调用。规范1.3规定的不能并发。
        private final AtomicBoolean on = new AtomicBoolean(false);

        /**
         * 跟踪当前请求
         * 记录订阅者的请求，这些请求还没有对订阅者回复
         */
        private long demand = 0;

        /**
         * 该订阅票据是否失效的标志
         */
        private boolean cancelled = false;

        SubscriptionImpl(final Subscriber<? super T> subscriber) {
            // 根据规范，如果Subscriber为null，需要抛空指针异常，此处抛null。
            if (subscriber == null) {
                throw null;
            }
            this.subscriber = subscriber;
        }


        /**
         * 主事件循环
         */
        @Override
        public void run() {

            // 与上次线程执行建立happens-before关系，防止并发执行
            // 如果on.get()为false，则不执行，线程退出
            // 如果on.get()为false，则表示没有线程在执行，当前线程可以执行

            if (on.get()) {
                try {
                    // 从队列取出一个入站信号
                    final Signal s = inboundSignals.poll();
                    // 规范1.8：如果`Subscription`被取消了，则必须最终停止向 `Subscriber`发送通知。
                    // 规范3.6：如果取消了`Subscription`，则随后调用 `Subscription.request(long n)`必须是无效的（NOPs）。
                    // 如果订阅票据没有取消
                    if (!cancelled) {
                        // 根据信号的类型调用对应的方法进行处理
                        if (s instanceof Request) {
                            // 请求
                            doRequest(((Request) s).n);
                        } else if (s == Send.Instance) {
                            // 发送
                            doSend();
                        } else if (s == Cancel.Instance) {
                            // 取消
                            doCancel();
                        } else if (s == Subscribe.Instance) {
                            // 订阅
                            doSubscribe();
                        }

                    }

                } finally {
                    // 保证与下一个线程调度的happens-before关系
                    on.set(false);
                    // 如果还有信号要处理
                    if (!inboundSignals.isEmpty()) {
                        // 调度当前线程进行处理
                        tryScheduleToExecute();
                    }

                }
            }
        }


        private void doSubscribe() {
            try {

                // 获取数据源的迭代器
                iterator = elements.iterator();
                if (iterator == null) {
                    // 如果iterator是null，就重置为空集合的迭代器。我们假设 iterator永远不是null值
                    iterator = Collections.<T>emptyList().iterator();
                }
            } catch (Throwable t) {

                // Publisher发生了异常，此时需要通知订阅者onError信号。
                // 但是规范1.9指定了在通知订阅者其他信号之前，必须先通知订阅者 onSubscribe信号。
                // 因此，此处通知订阅者onSubscribe信号，发送空的订阅票据

                subscriber.onSubscribe(new Subscription() {
                    @Override
                    public void request(long l) {
                        // 空的
                    }

                    @Override
                    public void cancel() {
                        // 空的
                    }
                });

                // 通知订阅者onError信号
                terminateDueTo(t);
            }


            if (!cancelled) {
                // 为订阅者设置订阅票据。
                try {
                    // 此处的this就是Subscription的实现类SubscriptionImpl的对象。
                    subscriber.onSubscribe(this);
                } catch (final Throwable t) {
                    // Publisher方法抛异常，此时需要通知订阅者onError信号。
                    // 但是根据规范2.13，通知订阅者onError信号之前必须先取消该订阅者的订阅票据。
                    // Publisher记录下异常信息。
                    terminateDueTo(new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onSubscribe.", t));
                }

                // 立即处理已经完成的迭代器
                boolean hasElements = false;
                try {
                    // 判断是否还有未发送的数据，如果没有，则向订阅者发送onComplete信号
                    hasElements = iterator.hasNext();
                } catch (final Throwable t) {
                    // 规范的1.4规定
                    // 如果hasNext发生异常，必须向订阅者发送onError信号，发送信号之前先取消订阅
                    // 规范1.2规定，Publisher通过向订阅者通知onError或onComplete信号，
                    // 发送少于订阅者请求的onNext信号。
                    terminateDueTo(t);
                }

                // 如果没有数据发送了，表示已经完成，直接发送onComplete信号终止订阅票据。
                // 规范1.3规定，通知订阅者onXxx信号，必须串行，不能并发。
                if (!hasElements) {
                    try {
                        // 规范1.6指明，在通知订阅者onError或onComplete信号之前，必须先取消订阅者的订阅票据。
                        // 在发送onComplete信号之前，考虑一下，有可能是Subscription取消了订阅。
                        doCancel();
                        subscriber.onComplete();
                    } catch (final Throwable t) {
                        // 规范2.13指出，onComplete信号不允许抛异常，因此此处只能记录下来日志
                        (new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onComplete.", t)).printStackTrace(System.err);
                    }
                }
            }
        }

        /**
         * Subscription.request的实现，
         * 接收订阅者的请求给Subscription，等待处理
         *
         * @param n 订阅者请求的元素数量
         */
        @Override
        public void request(long n) {
            signal(new Request(n));
        }

        // 该方法异步地给订阅票据发送指定信号
        private void signal(final Signal signal) {
            // 入站信号的队列，不需要检查是否为null，因为已经实例化过 ConcurrentLinkedQueue了
            // 将信号添加到入站信号队列中
            if (inboundSignals.offer(signal)) {
                // 信号入站成功，调度线程处理
                tryScheduleToExecute();
            }
        }

        /*** 规范1.6指出，`Publisher`在通知订阅者`onError`或者`onComplete`信号之 前，
         * **必须**先取消订阅者的订阅票据（`Subscription`）。
         * ** 当发送onError信号之前先取消订阅 * @param t
         * */
        private void terminateDueTo(final Throwable t) {
            // 当发送onError之前，先取消订阅票据
            cancelled = true;
            try {
                // 给下游Subscriber发送onError信号
                subscriber.onError(t);
            } catch (final Throwable t2) {
                // 规范1.9指出，onError不能抛异常。
                // 如果onError抛异常，只能记录信息。
                (new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onError.", t2)).printStackTrace(System.err);
            }
        }


        /**
         * 该方法确保订阅票据同一个时间在同一个线程运行
         * 规范1.3规定，调用`Subscriber`的`onSubscribe`，`onNext`，`onError`和 `onComplete`方法必须串行，不允许并发。
         */
        private final void tryScheduleToExecute() {
            // CAS原子性地设置on的值为true，表示已经有一个线程正在处理了
            if (on.compareAndSet(false, true)) {
                try {
                    // 向线程池提交任务运行
                    executor.execute(this);
                    // 如果不能使用Executor执行，则优雅退出
                } catch (Throwable t) {
                    if (!cancelled) {
                        // 首先，错误不可恢复，先取消订阅
                        doCancel();
                        try {
                            // 停止
                            terminateDueTo(new IllegalStateException("Publisher terminated due to unavailable Executor.", t));
                        } finally {
                            // 后续的入站信号不需要处理了，清空信号
                            inboundSignals.clear();
                            // 取消当前订阅票据，但是让该票据处于可调度状态，以防清 空入站信号之后又有入站信号加入。
                            on.set(false);
                        }
                    }
                }
            }
        }

        /**
         * 订阅者取消订阅。
         * Subscription.cancel的实现，用于通知Subscription，Subscriber不需要更 多元素了。
         */
        @Override
        public void cancel() {
            signal(Cancel.Instance);
        }

        /**
         * init方法的设置，用于确保SubscriptionImpl实例在暴露给线程池之前已经构造完成
         * 因此，在构造器一完成，就调用该方法，仅调用一次。
         * 先发个信号试一下
         */
        void init() {
            signal(Subscribe.Instance);
        }

        // 规范3.5指明，Subscription.cancel方法必须及时的返回，保持调用者的响应性， 还必须是幂等的，必须是线程安全的。
        // 因此该方法不能执行密集的计算。
        private void doCancel() {
            cancelled = true;
        }


        /**
         * 注册订阅者发送来的请求
         * 规范规定，如果请求的元素个数小于1，则抛异常
         * 并在异常信息中指明错误的原因：n必须是正整数。
         *
         * @param n 个数
         */
        private void doRequest(final long n) {

            if (n < 1) {
                terminateDueTo(new IllegalArgumentException(subscriber + " violated the Reactive Streams rule 3.9 by requesting a non-positive number of elements."));
            } else if (demand + n < 1) { // demaind + n < 1表示long型数字越界，表示订阅者请求的元素数量大于 Long.MAX_VALUE
                // 此时数据流认为是无界流。
                demand = Long.MAX_VALUE;
                // 开始向下游发送数据元素。
                doSend();
            } else {
                // 记录下游请求的元素个数
                demand += n;
                // 开始向下游发送数据元素。
                doSend();
            }
        }


        /**
         * 向下游发送元素的方法
         */
        private void doSend() {

            try {
                // 为了充分利用Executor，我们最多发送batchSize个元素，然后放弃当前 线程，重新调度，通知订阅者onNext信号。
                int leftInBatch = batchSize;

                do {
                    T next;
                    boolean hasNext;

                    try {
                        // 在订阅的时候已经调用过hasNext方法了，直接获取元素
                        next = iterator.next();

                        // 检查还有没有数据，如果没有，表示流结束了
                        hasNext = iterator.hasNext();

                    } catch (Throwable t) {
                        // 如果next方法或hasNext方法抛异常（用户提供的），认为流抛 异常了发送onError信号
                        terminateDueTo(t);
                        return;
                    }

                    // 向下游的订阅者发送onNext信号
                    subscriber.onNext(next);

                    if (!hasNext) {

                        // 首先考虑是票据取消了订阅
                        doCancel();

                        // 发送onComplete信号给订阅者
                        subscriber.onComplete();
                    }

                } while (!cancelled  // 如果没有取消订阅
                        && --leftInBatch > 0 // 如果还有剩余批次的元素
                        && --demand > 0 // 如果还有订阅者的请求
                );

                // 如果订阅票据没有取消，还有请求，通知自己发送更多的数据
                if (!cancelled && demand > 0) {
                    signal(Send.Instance);
                }
            } catch ( Throwable t) {

                // 如果到这里，只能是onNext或onComplete抛异常，只能取消。
                doCancel();

                // 记录错误信息
                (new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onNext or onComplete.", t)).printStackTrace(System.err);
            }

        }
    }
}
