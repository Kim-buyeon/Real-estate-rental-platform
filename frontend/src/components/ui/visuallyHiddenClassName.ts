import styles from './visuallyHidden.module.css';

/**
 * 화면에는 보이지 않고 화면 낭독기만 읽는 글자에 입히는 클래스. 규칙은 visuallyHidden.module.css 머리말이 갖는다.
 * 공용 UI 밖(도메인 컴포넌트)에서 쓸 때 CSS 모듈을 깊은 경로로 가져가지 않도록 index.ts 로 내보낸다 —
 * buttonClassName · kvRowClassName 과 같은 구조다. 클래스는 position: absolute 이므로 감싸는 요소가 기준 상자(position: relative)를 가져야 한다.
 */
export const visuallyHiddenClassName: string = styles.visuallyHidden ?? '';
