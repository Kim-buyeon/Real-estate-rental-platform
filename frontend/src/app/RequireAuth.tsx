import { Navigate, Outlet, useLocation } from 'react-router';
import { loginPath } from '../lib/routes';
import { useSession } from '../session/useSession';

/**
 * 인증 필수 화면의 가드. 세션이 없으면 /login?redirect=<원래 경로>로 보낸다 —
 * 라우터 state는 새로고침에 사라지므로 쿼리 파라미터로 둔다 (이슈 #81 계획).
 * 재발급 실패로 세션이 비워질 때도 useSession이 바뀌어 여기서 이동한다. api/client는 라우터를 모른다.
 * 로그인 뒤 redirect 경로로 돌려보내는 것은 LoginPage다.
 */
export function RequireAuth() {
  const { isAuthenticated } = useSession();
  const location = useLocation();
  if (!isAuthenticated) {
    return <Navigate to={loginPath(`${location.pathname}${location.search}`)} replace />;
  }
  return <Outlet />;
}
