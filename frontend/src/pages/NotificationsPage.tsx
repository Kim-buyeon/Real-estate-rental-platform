import { NotificationList } from '../features/notification';
import styles from './NotificationsPage.module.css';

/**
 * `/notifications` — NOTI-05 알림 목록. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 읽음 처리 · 더 보기는 NotificationList가 쿼리 정의를 거쳐 한다.
 *
 * 메뉴 묶음 「관심 목록」의 둘째 탭이다 — 페이지 제목과 탭은 묶음 머리(app/SectionLayout)가 그린다 (이슈 489).
 *
 * 실시간 수신(NOTI-03)은 이 화면의 일이 아니다 — 연결은 app/NotificationStream.tsx 하나이고,
 * 이 화면은 연결이 끊긴 상태에서도 그대로 동작한다 (알림 전달 문서 1.2).
 */
export default function NotificationsPage() {
  return (
    <section className={styles.page} aria-label="알림">
      <NotificationList />
    </section>
  );
}
