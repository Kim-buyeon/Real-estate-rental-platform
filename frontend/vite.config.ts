import { availableParallelism } from 'node:os'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

/*
 * 동시에 도는 테스트 파일 수의 상한 (이슈 121).
 *
 * 무관한 파일이 이따금 타임아웃성으로 흔들렸다 — 흔들린 것은 전부 waitFor · findBy*를 쓰는
 * 비동기 대기 테스트였고, CPU가 굶으면 단언 내용과 무관하게 Testing Library의 1초 시계만
 * 먼저 지난다. 이 기계는 16코어라 기본값이면 jsdom이 15개까지 동시에 뜬다.
 *
 * **왜 상수 4가 아니라 식인가.** Vitest는 수치로 준 maxWorkers를 코어 수로 깎지 않는다 —
 * resolveMaxWorkers가 설정값을 그대로 돌려주고, 클램프는 백분율 문자열 경로에만 있다.
 * 그래서 `maxWorkers: 4`는 상한이 아니라 하한으로도 작동한다. run 모드 기본값이
 * `max(코어-1, 1)`이므로 **4코어인 GitHub 러너에서는 3에서 4로 올리는 셈**이고, 막으려던
 * 경합을 더 좁은 기계에 들여놓게 된다. min을 직접 걸어 어느 기계에서도 기본값을 넘지 않게 한다 —
 * 이 기계에서 15 → 4, 러너에서 3 → 3(그대로).
 *
 * 백분율 문자열은 쓰지 않는다. 기계가 클수록 워커도 늘어 상한 구실을 못 한다.
 */
const TEST_MAX_WORKERS = Math.max(1, Math.min(4, availableParallelism() - 1))

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // .env는 저장소 루트 하나다 — frontend/CLAUDE.md 환경 변수
  envDir: '..',
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    /*
     * 4인 근거는 계측이다. 161건 전체를 두 묶음으로 쟀고 **묶음 사이에 기계 부하가 달라져
     * 절대 시간을 서로 비교하지 않는다.** 각 칸은 1~2회뿐이다.
     *
     *   한가할 때   기본값(15): 24.3s   4: 24.4s · 24.6s   6: 22.4s · 32.2s
     *   부하 중     16(명시): 54.2s     4: 37.7s · 40.6s
     *
     * 한가할 때 기본값을 4로 조여도 전체 시간이 평균 1% 안에서 같고(개별로는 +1.2%까지),
     * 부하가 있으면 4가 16보다 두 측정 모두 빨랐다(25% · 30%). 6은 두 측정이 9.8초
     * 벌어졌는데 **그 원인이 경합인지 기계 부하 변동인지는 2회로 가리지 못했다** — 다만
     * 4에서는 그만한 폭이 나오지 않았다. 관측한 두 상태에서 4가 손해를 본 적은 없다.
     *
     * 왜 병렬성을 더 줘도 빨라지지 않는가에 대해서는 Vitest의 Environment 진단이 단서를
     * 준다 — 기본값 실행에서 「jsdom was created 23 times · 203.74s total, 67% of tracked
     * time」이었다. tracked time은 워커별 합산이라 벽시계 비율이 아니다. 파일마다 jsdom을
     * 다시 만드는 비용이 크다는 것까지가 이 수치가 말하는 것이다.
     *
     * **흔들림 자체는 이번 계측 전부에서 재현되지 않았다** — 전 실행에서 161건이 통과했다.
     * 흔들림은 이슈 121의 관찰이고, 이 설정이 그것을 없앤다는 것은 확인된 바 없다.
     *
     * 타임아웃은 올리지 않는다. 시계를 늘리면 증상은 사라지지만 진짜로 멈춘 테스트도 오래
     * 기다리게 된다 (이슈 121 「지킬 선」).
     */
    maxWorkers: TEST_MAX_WORKERS,
  },
})
