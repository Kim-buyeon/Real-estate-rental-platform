package com.duri.rentalplatform.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커서 문자열의 형식을 정한다. 정렬 값과 식별자를 JSON 으로 묶어 Base64URL(패딩 없음)로 인코딩한다.
 *
 * <p>정렬 값은 문자열로 담는다. 정렬 기준마다 타입(정수 · 소수 · 일시)이 달라, 해석은 정렬 기준을 아는
 * 서비스가 한다. 정렬 값 없이 식별자만으로 넘기는 목록은 {@code value} 를 null 로 둔다.
 *
 * <p>클라이언트는 커서를 불투명 문자열로 다룬다(API 명세서 공통 규약 1.4). 깨진 커서는
 * {@link ErrorCode#INVALID_REQUEST} 로 거부한다.
 */
public final class CursorCodec {

    private static final JsonMapper JSON = new JsonMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String FIELD = "cursor";

    private CursorCodec() {
    }

    /**
     * 커서에 담기는 값. {@code s} 는 커서를 만든 정렬 기준과 방향, {@code v} 는 정렬 값, {@code id} 는 동률을
     * 가르는 식별자다.
     *
     * <p>{@code s} 가 있어야 다른 정렬로 받은 커서를 거부할 수 있다. 없으면 보증금 값이 전세가율로 읽혀
     * 틀린 페이지가 오류 없이 돌아온다.
     */
    public record Cursor(String s, String v, Long id) {
    }

    public static String encode(String sortValue, long id) {
        return encode(null, sortValue, id);
    }

    public static String encode(String sort, String sortValue, long id) {
        try {
            byte[] json = JSON.writeValueAsBytes(new Cursor(sort, sortValue, id));
            return ENCODER.encodeToString(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("커서 인코딩 실패", e);
        }
    }

    /** 커서를 해석한다. 형식이 틀리거나 식별자가 없으면 {@code INVALID_REQUEST}. */
    public static Cursor decode(String cursor) {
        try {
            byte[] json = DECODER.decode(cursor);
            Cursor decoded = JSON.readValue(new String(json, StandardCharsets.UTF_8), Cursor.class);
            if (decoded == null || decoded.id() == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, FIELD);
            }
            return decoded;
        } catch (IllegalArgumentException | JacksonException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, FIELD);
        }
    }
}
