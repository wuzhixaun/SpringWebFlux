import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Main11 {


    public static void main(String[] args) throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(1);

        // 返回订阅票据
        final Disposable subscribe = Observable.interval(100, TimeUnit.MICROSECONDS).subscribe(System.out::println);

        new Thread(()->{
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (!subscribe.isDisposed()) {
                subscribe.dispose();
            }

            countDownLatch.countDown();
            System.out.println("++++++++++++++++++++");
        }).start();

        System.out.println("====================");
        // 主线程阻塞等待
        countDownLatch.await();
    }
}
