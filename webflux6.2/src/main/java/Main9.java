import io.reactivex.rxjava3.core.Observable;

public class Main9 {

    public static void main(String[] args) {
        Observable.concat(
                Observable.just(1, 2, 3, 4),
                Observable.fromArray("A", "B"),
                Observable.create(subscribe -> {
                    for (int i = 0; i < 5; i++) {
                        subscribe.onNext(i);
                    }
                    subscribe.onComplete();

                })
        ).subscribe(item -> System.out.println("下一个元素" + item),
                ex -> System.out.println("异常信息" + ex.getMessage()),
                () -> System.out.println("结束"));
    }
}
