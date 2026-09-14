import { useSyncExternalStore } from 'react';
import { isAuthenticated, subscribe } from './store';

interface Session {
  isAuthenticated: boolean;
}

/** 로그인 상태만 읽는다. 토큰 값은 컴포넌트에 넘기지 않는다 */
export function useSession(): Session {
  const isLoggedIn = useSyncExternalStore(subscribe, isAuthenticated, isAuthenticated);
  return { isAuthenticated: isLoggedIn };
}
