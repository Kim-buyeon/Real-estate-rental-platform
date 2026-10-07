import { CardForm } from '../components/ui';
import { SubscriptionForm } from '../features/notification';
import styles from './SubscriptionsPage.module.css';

/**
 * `/me/notification-subscriptions` — NOTI-01 알림 구독 설정. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 SubscriptionForm이 쿼리 정의를 거쳐 한다.
 *
 * 메뉴 묶음 「내 정보」의 둘째 탭이다 — 페이지 제목과 탭은 묶음 머리(app/SectionLayout)가 그린다 (이슈 489).
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/my-info/layout.md 를 따른다 — /me/profile 과 같은 아키타입이다.
 */
export default function SubscriptionsPage() {
  return (
    <section className={styles.page} aria-label="알림 설정">
      <CardForm className={styles.card}>
        <SubscriptionForm />
      </CardForm>
    </section>
  );
}
