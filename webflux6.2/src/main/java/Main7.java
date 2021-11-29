import io.reactivex.rxjava3.core.Observable;

public class Main7 {

    public static void main(String[] args) {
        Observable.fromCallable(() -> "Hello").subscribe(System.out::println);

    }
}
