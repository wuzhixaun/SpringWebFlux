import io.reactivex.rxjava3.core.Observable;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main8 {

    public static void main(String[] args) {

        final Future<String> submit = Executors.newCachedThreadPool().submit(() -> "hello word");


        Observable.fromFuture(submit).subscribe(System.out::println);

    }
}
