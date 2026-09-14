import { Alert } from '../components/ui';
import styles from './HomePage.module.css';

/** `/` 자리 표시. 지도 탐색 화면(PROP-02 슬라이스)이 이 파일을 대체한다 */
export default function HomePage() {
  return (
    <section className={styles.page}>
      <Alert variant="info">지도 탐색 화면을 준비하고 있습니다.</Alert>
    </section>
  );
}
