import io.reactivex.rxjava3.core.Observable;

import java.util.Arrays;
import java.util.Collections;

public class Main6 {

    public static void main(String[] args) {


        final Observable<Integer> integerObservable = Observable.fromIterable(Arrays.asList(1, 2, 3, 4, 5));


        integerObservable.subscribe(
                item -> System.out.println("下一个元素" + item),
                ex -> System.out.println("异常信息" + ex.getMessage()),
                () -> System.out.println("结束")
        );

    }
}
