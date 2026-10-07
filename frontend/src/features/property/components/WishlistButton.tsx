import { Button, useToast } from '../../../components/ui';
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
 * 상세 패널 하단 {components.action-bar}의 {components.button-icon} 하나다 — 정의서 7절이 그 변형의
 * 쓰임을 「상세 패널 하단 공유 · 찜」으로 적었다. 라벨이 하트 아이콘이라 접근 이름(관심 등록 / 관심 해제)과
 * 눌림 상태(aria-pressed)를 따로 준다. 등록되면 하트를 채운다.
 *
 * 버튼만 갖는다. 비로그인 사유 문구는 바 높이(92)를 지키기 위해 패널이 바 밖에 낸다.
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
  const toast = useToast();

  // 지금 누르면 실행되는 쪽 하나만 본다 — 로딩이 그 요청의 것이다.
  const mutation = isWishlisted ? removeMutation : addMutation;

  return (
    <Button
      type="button"
      variant="icon"
      aria-label={isWishlisted ? '관심 해제' : '관심 등록'}
      aria-pressed={isWishlisted}
      onClick={() =>
        // 실패는 뮤테이션 실패라 Toast 다(frontend/CLAUDE.md 「뮤테이션 실패는 Toast」). 문구는 서버
        // error.message 그대로다 — 409 WISHLIST_DUPLICATED도 코드별 문구를 여기에 다시 적지 않는다
        mutation.mutate(propertyId, {
          onError: (failure) => toast.show(failure.message, { variant: 'error' }),
        })
      }
      isLoading={mutation.isPending}
      disabled={!isAuthenticated}
    >
      <svg className={styles.icon} viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M12 20.5 4.4 13a4.9 4.9 0 0 1 0-7 4.9 4.9 0 0 1 7 0l.6.6.6-.6a4.9 4.9 0 0 1 7 0 4.9 4.9 0 0 1 0 7Z"
          fill={isWishlisted ? 'currentColor' : 'none'}
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
      </svg>
    </Button>
  );
}
