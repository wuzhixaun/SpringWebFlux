import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.core.ObservableOnSubscribe;

public class Main {

    public static void main(String[] args) {


        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            for (int i = 0; i < 10; i++) {
                emitter.onNext("rxJava3-" + i);
            }
            // 结束
            emitter.onComplete();
        }).subscribe(
                System.out::println,
                System.err::println,
                System.out::println
        );


    }
}
