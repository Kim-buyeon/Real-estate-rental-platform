/*
 * vm 컨텍스트에 없는 Node 웹 스트림 전역을 채운다 — setupFiles 첫 자리에 둔다 (이슈 #126).
 *
 * src/ 밖 · tsconfig.node.json 쪽에 둔다 — 앱 코드가 아니라 테스트 실행 환경이고 Node 모듈(node:stream/web)을 쓴다.
 * 앱 tsconfig에는 Node 타입이 없다.
 *
 * pool: 'vmThreads'는 테스트 파일을 Node vm 컨텍스트에서 돌린다. 그 컨텍스트의 전역에는 jsdom이 올린 것만 있고
 * Node가 전역으로 주는 TransformStream 등이 없다. MSW가 모듈을 읽는 시점에 이것을 찾으므로 setup.ts가 MSW를
 * import하기 전에 채워야 한다 — import는 끌어올려지므로 같은 파일 안에서는 순서를 보장할 수 없어 파일을 나눈다.
 * 이미 있으면 덮지 않는다.
 */
import { ReadableStream, TransformStream, WritableStream } from 'node:stream/web';

const globals = globalThis as Record<string, unknown>;
globals.TransformStream ??= TransformStream;
globals.ReadableStream ??= ReadableStream;
globals.WritableStream ??= WritableStream;
