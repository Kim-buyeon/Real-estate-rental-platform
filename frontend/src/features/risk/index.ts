// 밖으로 내보내는 것. 다른 도메인(features/property의 상세 패널 · pages)은 이것만 import한다
// — 깊은 경로로 들어가지 않는다 (frontend/CLAUDE.md import 방향).

export { ConsistencyCheck, type ConsistencyCheckProps } from './components/ConsistencyCheck';
export { InsuranceProviders, type InsuranceProvidersProps } from './components/InsuranceProviders';
export { PersonalConditions, type PersonalConditionsProps } from './components/PersonalConditions';
export { ReanalysisButton } from './components/ReanalysisButton';
export { RegistryTimeline } from './components/RegistryTimeline';
export { RiskFindings, type RiskFindingsProps } from './components/RiskFindings';
export { RiskVerdict, type RiskVerdictProps } from './components/RiskVerdict';
