package com.duri.rentalplatform.domain.notification.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 전송을 소켓 대신 목록에 담는 연결 — 웹 계층 없이 보관소 · 구독자의 전송을 확인한다.
 *
 * <p>MVC 가 초기화하지 않은 {@link SseEmitter} 는 보낸 것을 내부 버퍼에 쌓을 뿐 밖에서 볼 수 없다. 그래서 이벤트를 만든 결과를
 * 여기서 가로챈다. {@link #failWith} 를 걸면 끊긴 연결처럼 입출력 예외를 던진다.
 */
public class CapturingSseEmitter extends SseEmitter {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private volatile IOException failure;

    public CapturingSseEmitter failWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        Set<DataWithMediaType> items = builder.build();
        if (failure != null) {
            throw failure;
        }
        StringBuilder text = new StringBuilder();
        List<Object> objects = new ArrayList<>();
        for (DataWithMediaType item : items) {
            if (item.getData() instanceof String s) {
                text.append(s);
            } else {
                text.append("<object>");
                objects.add(item.getData());
            }
        }
        events.add(new Event(text.toString(), List.copyOf(objects)));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * 보낸 이벤트 한 건.
     *
     * @param text    이벤트 줄. 직렬화할 본문 자리는 {@code <object>} 로 둔다
     * @param objects 직렬화 전 본문 객체
     */
    public record Event(String text, List<Object> objects) {
    }
}
