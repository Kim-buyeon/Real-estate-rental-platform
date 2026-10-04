import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 대조 한 번의 시간 — 로그인 요청에서 BCrypt 가 차지하는 몫의 하한을 본다(INF-06 #390, 단위 측정).
 *
 * <p>앱과 같은 인코더다 — {@code SecurityConfig.passwordEncoder()} 가 {@code new BCryptPasswordEncoder()}(강도 10, $2a)를 쓴다.
 * 해시 하나를 만든 뒤 예열 N 회, 이어서 {@code matches} M 회를 한 스레드에서 잰다. 다른 일을 하지 않는 JVM 의 값이라
 * 요청 안의 대조(스레드 경합 · GC · CPU 크레딧)보다 짧다 — 비교의 기준선이다.
 *
 * <p>클래스패스는 spring-security-crypto 7.1.1 · spring-core 7.0.9(상위 클래스의 {@code matches} 가 {@code StringUtils} 를 쓴다) ·
 * commons-logging 1.3.6(인코더가 로거를 만든다) — Spring Boot 4.1.1 BOM 의 판. 컴파일 · 실행 명령은 같은 폴더 위 README.
 *
 * <pre>
 *   java -cp &lt;jar들&gt;:. BcryptBench [예열 N=50] [측정 M=200] [강도=10]
 * </pre>
 *
 * 출력은 한 줄 요약과 키=값 줄(ms, 소수 셋째 자리). 비밀번호는 실행마다 고정 문자열이다 — 시험 계정과 무관하고 출력하지 않는다.
 */
public class BcryptBench {

    public static void main(String[] args) {
        int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int measured = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int strength = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(strength);
        // 가입 정책(8 ~ 64자 · 72바이트) 안의 길이. 길이는 BCrypt 시간에 영향이 없다(키 확장 반복 수가 강도로만 정해진다)
        String raw = "bench-password-0123456789";

        long encodeStart = System.nanoTime();
        String hash = encoder.encode(raw);
        double encodeMs = (System.nanoTime() - encodeStart) / 1e6;

        for (int i = 0; i < warmup; i++) {
            if (!encoder.matches(raw, hash)) {
                throw new IllegalStateException("예열 대조가 실패했다");
            }
        }

        double[] ms = new double[measured];
        for (int i = 0; i < measured; i++) {
            long t = System.nanoTime();
            boolean ok = encoder.matches(raw, hash);
            ms[i] = (System.nanoTime() - t) / 1e6;
            if (!ok) {
                throw new IllegalStateException("대조가 실패했다");
            }
        }
        Arrays.sort(ms);
        double sum = 0;
        for (double v : ms) {
            sum += v;
        }

        System.out.printf("BCrypt 강도 %d — 예열 %d회 · 측정 %d회 (JVM %s, CPU %d)%n", strength, warmup, measured,
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        System.out.printf("hash_prefix=%s%n", hash.substring(0, 7));
        System.out.printf("encode_first_ms=%.3f%n", encodeMs);
        System.out.printf("matches_mean_ms=%.3f%n", sum / measured);
        System.out.printf("matches_p50_ms=%.3f%n", percentile(ms, 50));
        System.out.printf("matches_p95_ms=%.3f%n", percentile(ms, 95));
        System.out.printf("matches_min_ms=%.3f%n", ms[0]);
        System.out.printf("matches_max_ms=%.3f%n", ms[ms.length - 1]);
    }

    /** 선형 보간 백분위 — 측정 집계(analyze)와 같은 규칙(numpy 기본 · PERCENTILE.INC). 정렬된 배열을 받는다. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }
}
