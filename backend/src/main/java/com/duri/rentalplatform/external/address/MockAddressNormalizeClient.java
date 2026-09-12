package com.duri.rentalplatform.external.address;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 주소 정규화 Mock. 같은 입력이면 항상 같은 결과를 준다.
 *
 * <p>하는 일은 실제 정규화가 보장하는 성질만 흉내 내는 것이다 — 공백을 하나로 줄이고, 자치구와
 * 법정동을 뽑아낸다. 도로명주소로 바꾸지는 못하므로 도로명 · 지번에 같은 문자열을 넣는다.
 * 이 값이 등기 · 대장 주소 대조에 쓰이므로, 표기 흔들림만 제거되면 Mock 의 목적은 달성된다.
 *
 * <p>자치구를 못 찾으면 빈 값을 돌려준다. 적재의 「건너뛰고 기록」 경로가 실제로 도는지 보려면
 * Mock 도 실패를 만들어 낼 수 있어야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "external.address-normalize", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockAddressNormalizeClient implements AddressNormalizeClient {

    private static final String DATA_SOURCE = "MOCK_JUSO";
    private static final String DISTRICT_SUFFIX = "구";
    private static final String LEGAL_DONG_SUFFIX = "동";

    @Override
    public Optional<NormalizedAddress> normalize(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawAddress.trim().replaceAll("\\s+", " ");
        List<String> tokens = Arrays.asList(normalized.split(" "));

        Optional<String> district = tokens.stream()
                .filter(token -> token.endsWith(DISTRICT_SUFFIX))
                .findFirst();
        if (district.isEmpty()) {
            return Optional.empty();
        }
        String legalDongName = tokens.stream()
                .filter(token -> token.endsWith(LEGAL_DONG_SUFFIX))
                .findFirst()
                .orElse(null);

        return Optional.of(new NormalizedAddress(
                normalized, normalized, district.get(), legalDongName, null, DATA_SOURCE));
    }
}
