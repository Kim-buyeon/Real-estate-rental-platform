import { Alert, Button } from '../../../components/ui';
import { useAddWishlist, useRemoveWishlist } from '../../../queries/property';
import { useSession } from '../../../session/useSession';
import styles from './WishlistButton.module.css';

interface WishlistButtonProps {
  propertyId: number;
  /** 현재 등록 여부. 출처는 매물 상세의 wishlisted다 — 화면이 목록을 받아 포함 여부를 계산하지 않는다 */
  isWishlisted: boolean;
}

/**
 * 관심 매물 등록 · 해제 (PROP-05). 인증 「필수」다 — 매물 API 명세 1장.
 *
 * 상세 패널 하단의 {components.action-bar} 안에 들어간다 — 카드가 아니라 버튼 하나와 그 결과
 * 문구다. 설명 문구를 두지 않는 것은 바 높이(92)가 그것을 담을 자리가 아니기 때문이다.
 *
 * 비로그인이면 버튼을 숨기지 않고 비활성으로 둔다. 숨기면 왜 없는지 알 수 없고, 로그인 화면으로
 * 보내면 지도 위치 · 확대 수준 · 필터와 열린 패널이 날아간다 — RISK-08 재분석 버튼과 같은 판단이다
 * (이슈 #91 · #96 계획 「정한 것」).
 *
 * 해제는 묻지 않고 바로 한다. 확인 절차를 두지 않는 이유는 되돌리기가 한 번 더 누르는 것이기 때문이다.
 *
 * 성공하면 관심 매물 목록과 이 매물의 상세가 무효화되어(queries/property.ts) 부모가 내려주는
 * isWishlisted가 스스로 바뀐다 — 여기서 등록 여부를 상태로 복사하지 않는다.
 */
export function WishlistButton({ propertyId, isWishlisted }: WishlistButtonProps) {
  const { isAuthenticated } = useSession();
  const addMutation = useAddWishlist();
  const removeMutation = useRemoveWishlist();

  // 지금 누르면 실행되는 쪽 하나만 본다 — 로딩 · 오류가 그 요청의 것이다.
  // 등록이 실패하면 isWishlisted가 그대로이므로 실패한 등록의 문구가 계속 보인다.
  const mutation = isWishlisted ? removeMutation : addMutation;

  return (
    <div className={styles.action}>
      <Button
        type="button"
        className={styles.button}
        variant={isWishlisted ? 'secondary' : 'primary'}
        onClick={() => mutation.mutate(propertyId)}
        isLoading={mutation.isPending}
        disabled={!isAuthenticated}
      >
        {isWishlisted ? '관심 해제' : '관심 등록'}
      </Button>

      {!isAuthenticated && (
        <p className={`${styles.note} type-body-sm`}>로그인하면 관심 매물로 등록할 수 있습니다.</p>
      )}

      {/* 문구는 서버 error.message 그대로다 — 409 WISHLIST_DUPLICATED도 코드별 문구를 여기에 다시
          적지 않는다. 뮤테이션 실패의 자리는 Toast지만 공용 UI에 아직 없어 재분석 버튼과 같이 Alert다 */}
      {mutation.error && <Alert variant="error">{mutation.error.message}</Alert>}
    </div>
  );
}
