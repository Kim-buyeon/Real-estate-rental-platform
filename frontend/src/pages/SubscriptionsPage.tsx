import { Card } from '../components/ui';
import { SubscriptionForm } from '../features/notification';
import styles from './SubscriptionsPage.module.css';

/**
 * `/me/notification-subscriptions` — NOTI-01 알림 구독 설정. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 SubscriptionForm이 쿼리 정의를 거쳐 한다.
 */
export default function SubscriptionsPage() {
  return (
    <section className={styles.page}>
      <Card className={styles.card}>
        <h1 className="type-heading-2">알림 설정</h1>
        <SubscriptionForm />
      </Card>
    </section>
  );
}
