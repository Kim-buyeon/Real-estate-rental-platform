import { setupServer } from 'msw/node';

// 도메인 핸들러는 test/msw/handlers/<도메인>.ts에 두고 여기서 모은다. 응답은 API 명세의 예시 그대로
export const server = setupServer();
