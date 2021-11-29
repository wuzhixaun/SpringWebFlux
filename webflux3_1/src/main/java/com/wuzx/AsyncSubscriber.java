package com.wuzx;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 基于Executor的异步运行的订阅者实现，一次请求一个元素，然后对每个元素调用用户定义的方法进行处理。
 * 注意：该类中使用了很多try-catch用于说明什么时候可以抛异常，什么时候不可以抛异常
 */
public abstract class AsyncSubscriber<T> implements Subscriber<T>, Runnable {

    // Signal表示发布者和订阅者之间的异步协议
    private static interface Signal {
    }

    // 表示数据流发送完成，完成信号
    private enum OnComplete implements Signal {Instance;}

    // 表示发布者给订阅者的异常信号
    private static class OnError implements Signal {
        public final Throwable error;

        public OnError(final Throwable error) {
            this.error = error;
        }
    }

    // 表示下一个数据项信号
    private static class OnNext<T> implements Signal {
        public final T next;

        public OnNext(final T next) {
            this.next = next;
        }
    }

    // 表示订阅者的订阅成功信号
    private static class OnSubscribe implements Signal {
        public final Subscription subscription;

        public OnSubscribe(final Subscription subscription) {
            this.subscription = subscription;
        }
    }

    // 订阅单据，根据规范3.1，该引用是私有的
    private Subscription subscription;

    // 用于表示当前的订阅者是否处理完成
    private boolean done;

    // 根据规范的2.2条款，使用该线程池异步处理各个信号
    private final Executor executor;

    /**
     * 仅有这一个构造器，只能被子类调用
     * 传递一个线程池即可
     * @param executor 线程池对象
     */
    protected AsyncSubscriber(Executor executor) {
        if (executor == null) throw null;
        this.executor = executor;
    }

    /**
     * 幂等地标记当前订阅者已完成处理，不再处理更多的元素。
     * 因此，需要取消订阅票据（Subscription）
     */
    private final void done() {
        // 在此处，可以添加done，对订阅者的完成状态进行设置；
        // 虽然规范3.7规定Subscription.cancel()是幂等的，我们不需要这么做。
        // 当whenNext方法抛异常，认为订阅者已经处理完成（不再接收更多元素）
        done = true;

        //
        if (subscription != null) { // If we are bailing out before we got a `Subscription` there's little need for cancelling it.
            try {
                // 取消订阅票据
                subscription.cancel();
            } catch (final Throwable t) {
                // 根据规范条款3.15，此处不能抛异常，因此只是记录下来。
                (new IllegalStateException(subscription + " violated the Reactive Streams rule 3.15 by throwing an exception from cancel.", t)).printStackTrace(System.err);
            }
        }
    }

    // This method is invoked when the OnNext signals arrive
    // Returns whether more elements are desired or not, and if no more elements are desired,
    // for convenience.

    /**
     *
     * @param element
     * @return
     */
    protected abstract boolean whenNext(final T element);

    // This method is invoked when the OnComplete signal arrives
    // override this method to implement your own custom onComplete logic.

    /**
     *
     */
    protected void whenComplete() {
    }

    // This method is invoked if the OnError signal arrives
    // override this method to implement your own custom onError logic.

    /**
     *
     * @param error
     */
    protected void whenError(Throwable error) {
    }

    /**
     *
     * @param s
     */
    private final void handleOnSubscribe(final Subscription s) {
        if (s == null) {
            // Getting a null `Subscription` here is not valid so lets just ignore it.
        } else if (subscription != null) { // If someone has made a mistake and added this Subscriber multiple times, let's handle it gracefully
            try {
                s.cancel(); // Cancel the additional subscription to follow rule 2.5
            } catch (final Throwable t) {
                //Subscription.cancel is not allowed to throw an exception, according to rule 3.15
                (new IllegalStateException(s + " violated the Reactive Streams rule 3.15 by throwing an exception from cancel.", t)).printStackTrace(System.err);
            }
        } else {
            // We have to assign it locally before we use it, if we want to be a synchronous `Subscriber`
            // Because according to rule 3.10, the Subscription is allowed to call `onNext` synchronously from within `request`
            subscription = s;
            try {
                // If we want elements, according to rule 2.1 we need to call `request`
                // And, according to rule 3.2 we are allowed to call this synchronously from within the `onSubscribe` method
                s.request(1); // Our Subscriber is unbuffered and modest, it requests one element at a time
            } catch (final Throwable t) {
                // Subscription.request is not allowed to throw according to rule 3.16
                (new IllegalStateException(s + " violated the Reactive Streams rule 3.16 by throwing an exception from request.", t)).printStackTrace(System.err);
            }
        }
    }

    /**
     *
     * @param element
     */
    private final void handleOnNext(final T element) {
        if (!done) { // If we aren't already done
            if (subscription == null) { // Technically this check is not needed, since we are expecting Publishers to conform to the spec
                // Check for spec violation of 2.1 and 1.09
                (new IllegalStateException("Someone violated the Reactive Streams rule 1.09 and 2.1 by signalling OnNext before `Subscription.request`. (no Subscription)")).printStackTrace(System.err);
            } else {
                try {
                    if (whenNext(element)) {
                        try {
                            subscription.request(1); // Our Subscriber is unbuffered and modest, it requests one element at a time
                        } catch (final Throwable t) {
                            // Subscription.request is not allowed to throw according to rule 3.16
                            (new IllegalStateException(subscription + " violated the Reactive Streams rule 3.16 by throwing an exception from request.", t)).printStackTrace(System.err);
                        }
                    } else {
                        done(); // This is legal according to rule 2.6
                    }
                } catch (final Throwable t) {
                    done();
                    try {
                        onError(t);
                    } catch (final Throwable t2) {
                        //Subscriber.onError is not allowed to throw an exception, according to rule 2.13
                        (new IllegalStateException(this + " violated the Reactive Streams rule 2.13 by throwing an exception from onError.", t2)).printStackTrace(System.err);
                    }
                }
            }
        }
    }

    // Here it is important that we do not violate 2.2 and 2.3 by calling methods on the `Subscription` or `Publisher`

    /**
     *
     */
    private void handleOnComplete() {
        if (subscription == null) { // Technically this check is not needed, since we are expecting Publishers to conform to the spec
            // Publisher is not allowed to signal onComplete before onSubscribe according to rule 1.09
            (new IllegalStateException("Publisher violated the Reactive Streams rule 1.09 signalling onComplete prior to onSubscribe.")).printStackTrace(System.err);
        } else {
            done = true; // Obey rule 2.4
            whenComplete();
        }
    }

    // Here it is important that we do not violate 2.2 and 2.3 by calling methods on the `Subscription` or `Publisher`

    /**
     *
     * @param error
     */
    private void handleOnError(final Throwable error) {
        if (subscription == null) { // Technically this check is not needed, since we are expecting Publishers to conform to the spec
            // Publisher is not allowed to signal onError before onSubscribe according to rule 1.09
            (new IllegalStateException("Publisher violated the Reactive Streams rule 1.09 signalling onError prior to onSubscribe.")).printStackTrace(System.err);
        } else {
            done = true; // Obey rule 2.4
            whenError(error);
        }
    }

    // We implement the OnX methods on `Subscriber` to send Signals that we will process asycnhronously, but only one at a time

    /**
     *
     * @param s
     */
    @Override
    public final void onSubscribe(final Subscription s) {
        // As per rule 2.13, we need to throw a `java.lang.NullPointerException` if the `Subscription` is `null`
        if (s == null) throw null;

        signal(new OnSubscribe(s));
    }

    /**
     *
     * @param element
     */
    @Override
    public final void onNext(final T element) {
        // As per rule 2.13, we need to throw a `java.lang.NullPointerException` if the `element` is `null`
        if (element == null) throw null;

        signal(new OnNext<T>(element));
    }

    /**
     *
     * @param t
     */
    @Override
    public final void onError(final Throwable t) {
        // As per rule 2.13, we need to throw a `java.lang.NullPointerException` if the `Throwable` is `null`
        if (t == null) throw null;

        signal(new OnError(t));
    }

    /**
     *
     */
    @Override
    public final void onComplete() {
        signal(OnComplete.Instance);
    }

    // This `ConcurrentLinkedQueue` will track signals that are sent to this `Subscriber`, like `OnComplete` and `OnNext` ,
    // and obeying rule 2.11
    /**
     *
     */
    private final ConcurrentLinkedQueue<Signal> inboundSignals = new ConcurrentLinkedQueue<Signal>();

    /**
     * 根据规范2.7和2.11，使用原子变量确保不会有多个订阅者线程并发执行。
     */
    private final AtomicBoolean on = new AtomicBoolean(false);

    /**
     *
     */
    @SuppressWarnings("unchecked")
    @Override
    public final void run() {
        // 跟上次线程执行建立happens-before关系，防止多个线程并发执行
        if (on.get()) {
            try {
                // 从入站队列取出信号
                final Signal s = inboundSignals.poll();
                // 根据规范条款2.8，如果当前订阅者已完成，就不需要处理了。
                if (!done) {
                    // 根据信号类型调用对应的方法来处理
                    if (s instanceof OnNext<?>)
                        handleOnNext(((OnNext<T>) s).next);
                    else if (s instanceof OnSubscribe)
                        handleOnSubscribe(((OnSubscribe) s).subscription);
                        // 根据规范2.10，必须处理onError信号，不管有没有调用过Subscription.request(long n)方法
                    else if (s instanceof OnError)
                        handleOnError(((OnError) s).error);
                        // 根据规范2.9，必须处理onComplete信号，不管有没有调用过Subscription.request(long n)方法
                    else if (s == OnComplete.Instance)
                        handleOnComplete();
                }
            } finally {
                // 保持happens-before关系，然后开始下一个线程调度执行
                on.set(false);
                // 如果入站信号不是空的，调度线程处理入站信号
                if (!inboundSignals.isEmpty())
                    // 调度处理入站信号
                    tryScheduleToExecute();
            }
        }
    }


    /**
     * What `signal` does is that it sends signals to the `Subscription` asynchronously
     * 该方法异步地向订阅票据发送信号
     * @param signal
     */
    private void signal(final Signal signal) {
        // 信号入站，线程池调度处理
        // 不需要检查是否为null，因为已经实例化了。
        if (inboundSignals.offer(signal))
            // 线程调度处理
            tryScheduleToExecute();
    }

    /**
     * 确保订阅者一次仅在一个线程执行
     * 调度执行
     */
    private final void tryScheduleToExecute() {
        // 使用CAS原子性地修改变量on的值改为true。
        if (on.compareAndSet(false, true)) {
            try {
                // 提交任务，多线程执行
                executor.execute(this);
            } catch (Throwable t) {
                // 根据规范条款2.13，如果不能执行线程池的提交方法，需要优雅退出
                if (!done) {
                    try {
                        // 由于错误不可恢复，因此取消订阅票据
                        done();
                    } finally {
                        // 不再需要处理入站信号，清空之
                        inboundSignals.clear();
                        // 由于订阅票据已经取消，但是此处依然让订阅者处于可调度的状态，以防在清空入站信号之后又有信号发送过来
                        // 因为信号的发送是异步的
                        on.set(false);
                    }
                }
            }
        }
    }
}
