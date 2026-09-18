import styles from './KvRow.module.css';

/**
 * 디자인 토큰 정의서 7절 `{components.kv-row}` 행의 격자 · 높이 · 하단 보더만 떼어낸 클래스.
 *
 * `<dl>` 구조가 아닌 곳이 쓴다 — 등기 이력은 같은 행 모양이지만 `<ol>` · `<li>` 목록이라
 * `<dt>` · `<dd>`를 쓸 수 없다. 두 번째 행 컴포넌트를 만들지 않고 클래스만 입힌다.
 *
 * KvRow.tsx와 파일을 나눈 것은 Fast Refresh 규칙(컴포넌트 파일은 컴포넌트만 내보낸다) 때문이다 —
 * 밖에서는 index.ts로 함께 가져간다. `buttonClassName`과 같은 구성이다.
 *
 * 색은 붙어 있지 않다. 쓰는 쪽이 자기 모듈에서 준다.
 */
// styles.row 는 CSS Modules 타입상 string | undefined 다 (noUncheckedIndexedAccess).
// 쓰는 쪽이 템플릿 문자열로 잇기 때문에 여기서 문자열로 좁힌다 — 'undefined' 가 클래스에 섞이지 않는다
export const kvRowClassName: string = styles.row ?? '';
