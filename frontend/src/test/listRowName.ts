// 매물 목록 행 버튼의 접근 이름 매처. 이름은 행 내용(가격 · 등급 · 주소 …)에 숨김 「상세 보기」가 붙은 꼴이라
// 정확 일치로 찾지 않는다 — 주소를 포함하고 「상세 보기」로 끝나는 것으로 그 행을 가린다.
export function listRowName(address: string): (accessibleName: string) => boolean {
  return (accessibleName) => accessibleName.includes(address) && accessibleName.endsWith('상세 보기');
}
