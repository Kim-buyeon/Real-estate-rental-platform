package com.duri.rentalplatform.domain.property.loader;

import com.duri.rentalplatform.domain.property.service.PropertyLoadService;
import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 매물 초기 적재 실행 경로. <b>{@code load-properties} 프로파일로 켤 때만 뜬다.</b>
 *
 * <p>기동:
 * <pre>./gradlew bootRun --args='--spring.profiles.active=load-properties --load.months=12'</pre>
 *
 * <p>평소 기동에서 이 빈은 만들어지지 않는다. 프로파일 없이 돌게 두면 배포마다 적재가 다시 도는데,
 * 그때마다 25개 구 × 12개월 × 2개 서비스 = 600회의 외부 호출이 나간다. 공공 API 는 일일 한도가 있다.
 *
 * <p>관리 API 로 두지 않은 이유는 범위다. 관리자 경로는 ADMIN-01 의 몫이고, 되풀이되는 갱신은
 * 갱신 배치(RISK-08)가 맡는다. 초기 적재는 한 번 돌리는 준비 작업이다 — 데이터 적재 설계서 1.4.
 */
@Slf4j
@Component
@Profile(PropertyLoadRunner.PROFILE)
@RequiredArgsConstructor
public class PropertyLoadRunner implements ApplicationRunner {

    public static final String PROFILE = "load-properties";

    /** 적재 기간 인자 이름. {@code --load.months=6} 처럼 준다. */
    private static final String MONTHS_ARGUMENT = "load.months";

    /**
     * 기본 적재 기간(개월). 12개월로 잡은 근거는 표본 수다 — 법정동 × 면적대로 나눈 뒤에도 중앙값이
     * 잡히려면 구간마다 여러 건이 필요하다. 실제 적재 건수를 보고 조정한다.
     */
    private static final int DEFAULT_MONTHS = 12;

    private final PropertyLoadService propertyLoadService;

    /**
     * 적재를 돌리고 결과를 남긴다.
     *
     * <p>집계를 여기서 만들어 넘기고 출력을 {@code finally} 에 두는 이유는, 적재가 무엇을 던지더라도
     * <b>그때까지의 건수와 실패 목록이 남게</b> 하기 위해서다. 반환값으로 받으면 예외가 난 순간
     * 기록이 통째로 사라진다.
     */
    @Override
    public void run(ApplicationArguments args) {
        int months = readMonths(args);
        log.info("[매물 적재] 시작 — 최근 {}개월", months);

        PropertyLoadReport report = new PropertyLoadReport();
        try {
            propertyLoadService.load(months, report);
        } finally {
            log.info("[매물 적재] 종료 — {}", report.summary());
            report.getFailures().forEach(failure -> log.warn("[매물 적재] 실패 — {}", failure));
        }
    }

    private int readMonths(ApplicationArguments args) {
        List<String> values = args.getOptionValues(MONTHS_ARGUMENT);
        if (values == null || values.isEmpty()) {
            return DEFAULT_MONTHS;
        }
        try {
            return Integer.parseInt(values.getFirst());
        } catch (NumberFormatException cause) {
            log.warn("[매물 적재] {} 값을 읽지 못해 기본값 {}개월로 돈다 — {}",
                    MONTHS_ARGUMENT, DEFAULT_MONTHS, values.getFirst());
            return DEFAULT_MONTHS;
        }
    }
}
