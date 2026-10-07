// SignupPage 제목 역할 클래스 — 원 역할과 모바일 역할이 함께 붙는지만 본다(CSS 값은 jsdom 에서 보지 않는다).
// 라우트 표는 다시 적지 않고 app/router 의 것을 얹는다(다른 페이지 테스트와 같은 방식).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from '../app/router';

describe('SignupPage 제목 역할 클래스', () => {
  it('h1 에 원 역할과 모바일 역할 클래스가 함께 붙는다', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const router = createMemoryRouter(routes, { initialEntries: ['/signup'] });
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    const h1 = await screen.findByRole('heading', { level: 1 });
    expect(h1).toHaveClass('type-heading-1', 'type-heading-1-mobile');
  });
});
