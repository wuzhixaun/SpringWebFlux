import io.reactivex.rxjava3.core.Observable;

import java.util.concurrent.TimeUnit;

public class Main10 {


    public static void main(String[] args) throws InterruptedException {

        // 创建基于时间的异步响应式流
        Observable.interval(1, TimeUnit.SECONDS).subscribe(System.out::println);
        Thread.sleep(5000);

    }
}
