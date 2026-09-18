import { CardForm } from '../components/ui';
import { SubscriptionForm } from '../features/notification';
import styles from './SubscriptionsPage.module.css';

/**
 * `/me/notification-subscriptions` — NOTI-01 알림 구독 설정. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 SubscriptionForm이 쿼리 정의를 거쳐 한다.
 *
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/my-info/layout.md 를 따른다 — /me/profile 과 같은
 * 아키타입이다. 참고 사이트와 달리 우리는 탭이 아니라 라우트 둘이라 탭 줄이 없다.
 */
export default function SubscriptionsPage() {
  return (
    <section className={styles.page}>
      <h1 className={`${styles.title} type-display`}>알림 설정</h1>
      <CardForm className={styles.card}>
        <SubscriptionForm />
      </CardForm>
    </section>
  );
}
