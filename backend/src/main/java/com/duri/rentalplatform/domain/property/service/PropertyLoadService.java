package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.domain.property.calculator.ContractTypeClassifier;
import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.calculator.MarketPriceCalculator;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.SeoulDistrict;
import com.duri.rentalplatform.domain.property.vo.LedgerKeyFill;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.MarketPriceUpdate;
import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyPriceSnapshot;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.Coordinates;
import com.duri.rentalplatform.external.address.GeocodeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import com.duri.rentalplatform.external.realestate.SaleBuildingType;
import com.duri.rentalplatform.external.realestate.SaleTransaction;
import com.duri.rentalplatform.external.realestate.SaleTransactionClient;
import com.duri.rentalplatform.external.realestate.SaleTransactionQuery;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 매물 초기 적재. 전월세 실거래가를 모아 주소를 정규화하고 좌표를 확보한 뒤 매물로 저장한다 —
 * 데이터 적재 설계서 1.4. 매물의 시세는 같은 자치구 · 같은 기간 · 같은 유형의 <b>매매</b> 실거래가로 만든 시세표에서 찾는다
 * ({@link MarketPriceCalculator}).
 *
 * <p><b>QueryService · CommandService 로 나누지 않은 이유</b> — 이 클래스는 사용자 요청을 처리하지
 * 않는다. 적재는 서비스 기능이 아니라 개발 · 운영 준비 작업이며(같은 절), 매물을 등록하는 API 경로는
 * 두지 않는다. 적재 서비스를 {@code <도메인>LoadService} + {@code <도메인>LoadWriter} 로 두는 것은
 * {@code backend/CLAUDE.md} Service 절이 적은 예외다. 저장은 {@link PropertyLoadWriter} 가 맡고
 * 여기에는 트랜잭션이 없다 — <b>외부 호출을 트랜잭션 안에 두지 않기 위해서</b>다.
 *
 * <p><b>실패를 다루는 방식</b> — 「적재 실패한 항목은 건너뛰고 기록한다. 일부 실패가 전체 적재를
 * 중단시키지 않는다」(같은 절)를 네 층으로 지킨다.
 *
 * <ol>
 *   <li>한 구 · 한 달의 조회(전월세 · 매매 각각)가 실패해도 나머지 달로 넘어간다.</li>
 *   <li>필수 값이 빠진 전월세 한 건은 매물로 옮기기 전에 걸러 낸다 — 면적이나 보증금이 없으면
 *       면적대 분류와 금액 계산에서 예외가 난다. 매매 표본의 빈 건은 시세 산출이 표본에서 뺀다.</li>
 *   <li>한 건을 옮기다 무엇이 나든 그 건만 버린다. 예상하지 못한 예외까지 잡는 이유는, 한 건의
 *       자료 이상이 25개 구 전체를 멈추게 두지 않기 위해서다.</li>
 *   <li>저장 한 덩어리가 실패하거나 한 자치구가 통째로 멈춰도 다음 자치구는 돈다.</li>
 * </ol>
 *
 * <p>무엇을 몇 건 버렸는지는 {@link PropertyLoadReport} 에 남고 실행이 끝난 뒤 한 번에 출력된다.
 *
 * <p><b>초기 적재와 갱신 적재</b> — 수집 · 정규화 · 시세 산출 · 신규 저장은 같다. 다른 것은 자연키가 같은 기존 매물을 만났을
 * 때다. 초기 적재({@link #load})는 건너뛰기만 하고, 갱신 적재({@link #refresh}, 갱신 배치 RISK-08)는 새로 계산한 시세 ·
 * 기준일이 저장값과 다를 때만 시세를 갱신한다 — 데이터 적재 설계서 1.5. 갱신 적재는 신규 매물과 시세 금액이 바뀐 매물의
 * 식별자를 돌려주어 판정 단계가 이어받게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyLoadService {

    /** 한 트랜잭션에 넣는 건수. 한 구를 통째로 한 트랜잭션에 담으면 커넥션을 오래 붙든다. */
    private static final int SAVE_CHUNK_SIZE = 500;

    private static final String SEOUL = "서울특별시";

    private final RentTransactionClient rentTransactionClient;
    private final SaleTransactionClient saleTransactionClient;
    private final AddressNormalizeClient addressNormalizeClient;
    private final GeocodeClient geocodeClient;
    private final PropertyLoadWriter propertyLoadWriter;

    /**
     * 서울 25개 자치구의 최근 {@code months} 개월 실거래를 적재한다.
     *
     * <p>집계를 만들어 돌려주지 않고 <b>받아서 채운다.</b> 여기서 만들어 반환하면 무엇이 던져졌을 때
     * 그때까지의 건수와 실패 목록이 호출자에게 닿지 못하고 사라진다. 호출자가 들고 있으면 예외가
     * 나가도 남은 기록을 출력할 수 있다.
     *
     * @param months 오늘이 속한 달의 직전 달부터 거슬러 올라갈 개월 수. 이번 달은 신고분이 거의 없다
     * @param report 적재 결과 집계. 호출자가 만들어 넘긴다
     */
    public void load(int months, PropertyLoadReport report) {
        loadAll(months, report, null);
    }

    /**
     * 갱신 배치(RISK-08)의 적재 단계. 초기 적재와 같은 기간을 다시 모아 새 매물을 저장하고, 자연키가 같은 기존 매물은 새로
     * 계산한 시세 · 기준일이 저장값과 다를 때만 갱신한다. 같으면 손대지 않는다 — 데이터 적재 설계서 1.5.
     *
     * <p><b>기간을 초기 적재와 같게 받는 이유</b> — 시세는 표본의 중앙값이다. 기간이 다르면 표본이 달라져, 실제로는 바뀌지 않은
     * 매물까지 「변경」으로 잡힌다.
     *
     * <p><b>수집이 일부 실패한 자치구</b> — 전월세든 매매든 한 달이라도 못 받았으면 그 자치구의 기존 매물 시세는 갱신하지 않는다. 빠진 달이 있으면 표본이 줄어 중앙값이
     * 달라지므로, 반영하면 멀쩡한 시세를 덜 모은 표본의 값으로 덮게 된다 — 같은 설계서 「갱신 배치는 실패해도 기존 데이터를
     * 훼손하지 않는다」. 새 매물 저장은 초기 적재와 같이 진행한다.
     *
     * <p><b>건축물대장 조회 키 이행</b> — 자연키로 만난 기존 매물의 조회 키가 비어 있으면 이번 회차에 만든 키로 채운다. 이미
     * 받는 실거래 · 주소 응답에서 나오는 값이라 외부 호출이 늘지 않는다. 조회 기간 밖으로 빠진 매물은 만나지 않으므로 계속 빈다.
     *
     * @param months 초기 적재와 같은 뜻의 기간(개월)
     * @param report 적재 결과 집계. 호출자가 만들어 넘긴다 — {@link #load} 와 같은 이유
     * @return 새로 저장한 매물과 시세 금액이 바뀐 매물의 식별자
     */
    public PropertyRefreshResult refresh(int months, PropertyLoadReport report) {
        RefreshTargets targets = new RefreshTargets();
        loadAll(months, report, targets);
        return new PropertyRefreshResult(targets.newPropertyIds, targets.priceChangedPropertyIds);
    }

    /**
     * @param refreshTargets 갱신 적재면 식별자를 모을 자리, 초기 적재면 null. null 이면 기존 매물을 건너뛰기만 한다
     */
    private void loadAll(int months, PropertyLoadReport report, RefreshTargets refreshTargets) {
        List<YearMonth> targetMonths = recentMonths(months);

        for (SeoulDistrict district : SeoulDistrict.values()) {
            try {
                loadDistrict(district, targetMonths, report, refreshTargets);
            } catch (RuntimeException cause) {
                // 한 자치구에서 무엇이 나든 남은 자치구는 돈다. 여기서 막지 않으면 적재가 통째로 끝난다.
                String reason = "자치구 적재 중단 — %s (%s)"
                        .formatted(district.getDistrictName(), cause);
                report.failUnexpected(reason);
                log.warn("[매물 적재] {}", reason, cause);
            }
            log.info("[매물 적재] {} 완료 — {}", district.getDistrictName(), report.summary());
        }
    }

    private List<YearMonth> recentMonths(int months) {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        List<YearMonth> targetMonths = new ArrayList<>(months);
        for (int offset = 0; offset < months; offset++) {
            targetMonths.add(lastMonth.minusMonths(offset));
        }
        return targetMonths;
    }

    /**
     * 자치구 하나를 적재한다.
     *
     * <p>구 단위로 도는 이유는 시세 때문이다. 시세는 같은 법정동 · 같은 면적대 표본의 중앙값이라
     * 표본이 한자리에 모여 있어야 계산된다. 달 단위로 저장하면 그 달의 표본만으로 중앙값을 내게 된다.
     *
     * <p>매물 후보(전월세)를 먼저 받고, 쓸 수 있는 후보가 없으면 매매는 부르지 않는다 — 시세표를 쓸 곳이 없는데 달 수 × 유형
     * 수만큼 호출하게 된다. 매매는 전월세와 같은 기간(달 목록)으로 받는다.
     */
    private void loadDistrict(SeoulDistrict district, List<YearMonth> targetMonths,
                              PropertyLoadReport report, RefreshTargets refreshTargets) {
        int externalFailuresBefore = report.getFailedExternal();
        List<RentTransaction> fetched = fetchTransactions(district, targetMonths, report);
        report.addFetched(fetched.size());

        List<RentTransaction> transactions = filterUsable(fetched, district, report);
        if (transactions.isEmpty()) {
            return;
        }

        List<SaleTransaction> sales = fetchSaleTransactions(district, targetMonths, report);
        boolean fetchComplete = report.getFailedExternal() == externalFailuresBefore;
        MarketPriceCalculator marketPrices = MarketPriceCalculator.from(sales);

        // 갱신 적재에서만 저장된 시세를 읽는다. 기존 매물을 처음 만날 때 꺼내 비교하고 지우므로, 같은 자연키가 다시 나오면
        // 비교 없이 중복으로만 센다. 수집이 일부 실패한 자치구는 비교 대상을 비워 시세를 갱신하지 않는다(refresh 주석).
        //
        // 건축물대장 조회 키가 비어 있는 기존 매물은 따로 모아 두고, 자연키로 다시 만나면 이번 회차의 키로 채운다(V17 이전 매물의
        // 이행). 시세와 달리 수집이 일부 실패해도 채운다 — 키는 한 건의 실거래 · 주소 응답에서 나오고 표본 크기와 무관하다.
        Map<PropertyNaturalKey, PropertyPriceSnapshot> storedPrices = new HashMap<>();
        Map<PropertyNaturalKey, Long> keylessPropertyIds = new HashMap<>();
        Set<PropertyNaturalKey> seenKeys;
        if (refreshTargets == null) {
            seenKeys = new HashSet<>(propertyLoadWriter.findLoadedNaturalKeys(district.getDistrictName()));
        } else {
            storedPrices.putAll(propertyLoadWriter.findLoadedPrices(district.getDistrictName()));
            seenKeys = new HashSet<>(storedPrices.keySet());
            storedPrices.forEach((key, stored) -> {
                if (stored.ledgerKeyMissing()) {
                    keylessPropertyIds.put(key, stored.propertyId());
                }
            });
            if (!fetchComplete) {
                log.warn("[매물 갱신] {} — 실거래가 수집이 일부 실패해 기존 매물 시세는 갱신하지 않는다",
                        district.getDistrictName());
                storedPrices.clear();
            }
        }
        List<MarketPriceUpdate> pendingUpdates = new ArrayList<>();
        List<LedgerKeyFill> pendingKeyFills = new ArrayList<>();

        // 같은 건물 · 같은 지번이 여러 번 나온다. 구 단위 실행 동안만 사는 메모라 인스턴스 간에
        // 공유할 상태가 아니다. 이것이 없으면 같은 주소를 수십 번 외부에 묻는다.
        Map<String, Optional<NormalizedAddress>> addressMemo = new HashMap<>();
        Map<String, Optional<Coordinates>> coordinateMemo = new HashMap<>();

        List<PropertyRegistration> pending = new ArrayList<>(SAVE_CHUNK_SIZE);
        for (RentTransaction transaction : transactions) {
            PropertyRegistration registration = toRegistrationSafely(
                    transaction, district, marketPrices, addressMemo, coordinateMemo, report);
            if (registration == null) {
                continue;
            }
            if (!seenKeys.add(registration.naturalKey())) {
                report.skipDuplicate();
                PropertyPriceSnapshot stored = storedPrices.remove(registration.naturalKey());
                if (stored != null && priceDiffers(stored, registration)) {
                    pendingUpdates.add(new MarketPriceUpdate(stored.propertyId(), registration.marketPrice(),
                            registration.priceType(), registration.priceDate()));
                    if (pendingUpdates.size() >= SAVE_CHUNK_SIZE) {
                        updatePriceChunk(pendingUpdates, district, report, refreshTargets);
                        pendingUpdates.clear();
                    }
                }
                Long keylessId = registration.ledgerKey() == null
                        ? null : keylessPropertyIds.remove(registration.naturalKey());
                if (keylessId != null) {
                    pendingKeyFills.add(new LedgerKeyFill(keylessId, registration.ledgerKey()));
                    if (pendingKeyFills.size() >= SAVE_CHUNK_SIZE) {
                        fillLedgerKeyChunk(pendingKeyFills, district, report);
                        pendingKeyFills.clear();
                    }
                }
                continue;
            }
            pending.add(registration);

            if (pending.size() >= SAVE_CHUNK_SIZE) {
                saveChunk(pending, district, report, refreshTargets);
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            saveChunk(pending, district, report, refreshTargets);
        }
        if (!pendingUpdates.isEmpty()) {
            updatePriceChunk(pendingUpdates, district, report, refreshTargets);
        }
        if (!pendingKeyFills.isEmpty()) {
            fillLedgerKeyChunk(pendingKeyFills, district, report);
        }
    }

    /** 새로 계산한 시세 · 산출 근거 · 기준일 중 하나라도 저장값과 다른가. */
    private boolean priceDiffers(PropertyPriceSnapshot stored, PropertyRegistration registration) {
        return !Objects.equals(stored.marketPrice(), registration.marketPrice())
                || stored.priceType() != registration.priceType()
                || !Objects.equals(stored.priceDate(), registration.priceDate());
    }

    /**
     * 한 자치구의 대상 기간 전월세 실거래를 모은다. 매물 후보다. 유형별로 서비스가 다르므로 유형 수 × 달 수만큼 호출한다.
     */
    private List<RentTransaction> fetchTransactions(SeoulDistrict district, List<YearMonth> targetMonths,
                                                    PropertyLoadReport report) {
        List<RentTransaction> transactions = new ArrayList<>();
        for (RentBuildingType buildingType : RentBuildingType.values()) {
            for (YearMonth yearMonth : targetMonths) {
                try {
                    transactions.addAll(rentTransactionClient.findRentTransactions(
                            new RentTransactionQuery(district.getLawdCode(), yearMonth, buildingType)));
                } catch (BusinessException cause) {
                    // 한 달을 못 받았다고 나머지 열한 달을 버리지 않는다.
                    String reason = "실거래가 조회 실패 — %s %s %s"
                            .formatted(district.getDistrictName(), yearMonth, buildingType);
                    report.failExternal(reason);
                    log.warn("[매물 적재] {}", reason);
                }
            }
        }
        return transactions;
    }

    /**
     * 한 자치구의 대상 기간 매매 실거래를 모은다. 시세표의 표본이다. 전월세와 같이 유형 수 × 달 수만큼 호출하고, 한 달을 못
     * 받아도 나머지 달은 받는다.
     *
     * <p>해제 거래 · 필수 값이 빈 거래는 여기서 거르지 않는다 — 표본에 넣을지는 {@link MarketPriceCalculator} 가 정한다.
     * 매매 거래는 매물이 아니라 표본이라, 한 건이 표본에서 빠지는 것을 적재 실패(자료이상)로 세지 않는다.
     */
    private List<SaleTransaction> fetchSaleTransactions(SeoulDistrict district, List<YearMonth> targetMonths,
                                                        PropertyLoadReport report) {
        List<SaleTransaction> transactions = new ArrayList<>();
        for (SaleBuildingType buildingType : SaleBuildingType.values()) {
            for (YearMonth yearMonth : targetMonths) {
                try {
                    transactions.addAll(saleTransactionClient.findSaleTransactions(
                            new SaleTransactionQuery(district.getLawdCode(), yearMonth, buildingType)));
                } catch (BusinessException cause) {
                    String reason = "매매 실거래가 조회 실패 — %s %s %s"
                            .formatted(district.getDistrictName(), yearMonth, buildingType);
                    report.failExternal(reason);
                    log.warn("[매물 적재] {}", reason);
                }
            }
        }
        return transactions;
    }

    /**
     * 필수 값이 빠진 건을 걸러 낸다.
     *
     * <p>{@code RentTransaction} 은 제공처가 비워 보내는 필드를 그대로 받는 레코드라 null 을 막지
     * 않는다. 걸러 내지 않으면 시세 조회의 면적대 분류와 금액 계산에서 예외가 난다. 건별 변환이 그 예외를 잡더라도
     * 「변환 실패」로 뭉뚱그려져, 어느 필수 값이 비었는지가 기록에 남지 않는다.
     */
    private List<RentTransaction> filterUsable(List<RentTransaction> transactions, SeoulDistrict district,
                                               PropertyLoadReport report) {
        List<RentTransaction> usable = new ArrayList<>(transactions.size());
        for (RentTransaction transaction : transactions) {
            String missingField = missingRequiredField(transaction);
            if (missingField == null) {
                usable.add(transaction);
                continue;
            }
            report.failInvalidData("필수 값 누락(%s) — %s"
                    .formatted(missingField, describe(district, transaction)));
        }
        return usable;
    }

    /** 없으면 처리할 수 없는 값. 하나라도 비면 그 이름을 돌려준다. */
    private String missingRequiredField(RentTransaction transaction) {
        if (transaction.areaSqm() == null) {
            return "전용면적";
        }
        if (transaction.deposit() == null) {
            return "보증금";
        }
        if (transaction.contractDate() == null) {
            return "계약일";
        }
        return null;
    }

    /**
     * 한 건을 옮긴다. 예상하지 못한 예외가 나도 그 건만 버리고 다음으로 넘어간다.
     *
     * <p>{@link BusinessException} 만 잡으면 자료 한 건의 이상이 자치구 · 나아가 전체 적재를 멈춘다.
     */
    private PropertyRegistration toRegistrationSafely(
            RentTransaction transaction,
            SeoulDistrict district,
            MarketPriceCalculator marketPrices,
            Map<String, Optional<NormalizedAddress>> addressMemo,
            Map<String, Optional<Coordinates>> coordinateMemo,
            PropertyLoadReport report) {

        try {
            return toRegistration(transaction, district, marketPrices, addressMemo, coordinateMemo, report);
        } catch (RuntimeException cause) {
            String reason = "매물 변환 실패 — %s (%s)".formatted(describe(district, transaction), cause);
            report.failInvalidData(reason);
            log.warn("[매물 적재] {}", reason, cause);
            return null;
        }
    }

    /**
     * 실거래 한 건을 저장 직전 형태로 옮긴다. 건너뛸 건이면 {@code null}.
     */
    private PropertyRegistration toRegistration(
            RentTransaction transaction,
            SeoulDistrict district,
            MarketPriceCalculator marketPrices,
            Map<String, Optional<NormalizedAddress>> addressMemo,
            Map<String, Optional<Coordinates>> coordinateMemo,
            PropertyLoadReport report) {

        String rawAddress = composeRawAddress(district, transaction);

        Optional<NormalizedAddress> normalized;
        try {
            normalized = addressMemo.computeIfAbsent(rawAddress, addressNormalizeClient::normalize);
        } catch (BusinessException cause) {
            report.failExternal("주소 정규화 실패 — " + rawAddress);
            return null;
        }
        if (normalized.isEmpty()) {
            report.skipAddressNotFound();
            return null;
        }
        String address = normalized.get().roadAddress();

        Optional<Coordinates> coordinates;
        try {
            coordinates = coordinateMemo.computeIfAbsent(address, geocodeClient::geocode);
        } catch (BusinessException cause) {
            report.failExternal("좌표 조회 실패 — " + address);
            return null;
        }
        if (coordinates.isEmpty()) {
            report.skipCoordinatesNotFound();
            return null;
        }

        Optional<MarketPriceCalculator.MarketPrice> marketPrice =
                marketPrices.find(toPropertyType(transaction.buildingType()), transaction.legalDongName(),
                        transaction.areaSqm());
        if (marketPrice.isEmpty()) {
            // 시세는 깡통전세 판정 기준금액의 밑값이자 전세가율의 분모다. 같은 법정동 · 자치구 어디에도 같은 유형 · 같은
            // 면적대의 매매 표본이 없으면 값을 지어내지 않고 그 매물을 버린다.
            report.skipMarketPriceNotFound();
            return null;
        }

        long deposit = transaction.deposit();
        long monthlyRent = transaction.monthlyRent() == null ? 0L : transaction.monthlyRent();
        ContractType contractType = ContractTypeClassifier.classify(deposit, monthlyRent);
        PropertyNaturalKey naturalKey = new PropertyNaturalKey(
                address, transaction.areaSqm(), transaction.floor(), deposit, monthlyRent);

        return new PropertyRegistration(
                address,
                district.getDistrictName(),
                LandlordNameGenerator.generate(naturalKey),
                contractType,
                toPropertyType(transaction.buildingType()),
                deposit,
                monthlyRent,
                marketPrice.get().amount(),
                PriceType.ACTUAL_TRANSACTION,
                basePriceDate(marketPrice.get().baseDate()),
                transaction.areaSqm(),
                transaction.floor(),
                transaction.buildYear(),
                coordinates.get().latitude(),
                coordinates.get().longitude(),
                // 시군구 코드는 실거래 응답, 법정동 코드는 주소 정규화 응답에서 온다. 못 만들면 null 로 두고 매물은 저장한다 —
                // 대장 조회 키는 대장을 뗄 때만 쓰이고 매물 탐색 · 시세와 무관하다.
                LedgerLookupKey.of(transaction.lawdCode(), normalized.get().legalDongCode(), transaction.jibun())
                        .orElse(null));
    }

    /**
     * 기존 매물 한 덩어리에 건축물대장 조회 키를 채운다. 실패하면 그 덩어리만 버린다 — 저장 덩어리와 같은 이유다. 버린
     * 덩어리의 매물은 키가 빈 채라 다음 회차에 다시 채운다.
     */
    private void fillLedgerKeyChunk(List<LedgerKeyFill> pending, SeoulDistrict district, PropertyLoadReport report) {
        try {
            report.addLedgerKeyFilled(propertyLoadWriter.fillLedgerKeys(pending));
        } catch (RuntimeException cause) {
            String reason = "대장 조회 키 보강 실패 — %s %d건 (%s)"
                    .formatted(district.getDistrictName(), pending.size(), cause);
            report.failUnexpected(reason);
            log.warn("[매물 갱신] {}", reason, cause);
        }
    }

    /**
     * 한 덩어리를 저장한다. 실패하면 그 덩어리만 버리고 다음 덩어리로 넘어간다.
     *
     * <p>여기서 막지 않으면 덩어리 하나의 저장 실패가 자치구 반복문 밖으로 나가, 그때까지의 건수와
     * 실패 목록이 출력되기 전에 실행이 끝난다.
     */
    private void saveChunk(List<PropertyRegistration> pending, SeoulDistrict district,
                           PropertyLoadReport report, RefreshTargets refreshTargets) {
        List<Long> savedIds;
        try {
            savedIds = propertyLoadWriter.saveAll(pending);
        } catch (RuntimeException cause) {
            String reason = "저장 실패 — %s %d건 (%s)"
                    .formatted(district.getDistrictName(), pending.size(), cause);
            report.failUnexpected(reason);
            log.warn("[매물 적재] {}", reason, cause);
            return;
        }
        report.addSaved(savedIds.size());
        if (refreshTargets != null) {
            refreshTargets.newPropertyIds.addAll(savedIds);
        }
    }

    /**
     * 기존 매물 한 덩어리의 시세를 갱신한다. 실패하면 그 덩어리만 버린다 — 저장 덩어리와 같은 이유다. 버린 덩어리의 매물은
     * 저장값이 그대로이므로 다음 회차에 다시 비교된다.
     */
    private void updatePriceChunk(List<MarketPriceUpdate> pending, SeoulDistrict district,
                                  PropertyLoadReport report, RefreshTargets refreshTargets) {
        List<Long> priceChangedIds;
        try {
            priceChangedIds = propertyLoadWriter.updateMarketPrices(pending);
        } catch (RuntimeException cause) {
            String reason = "시세 갱신 실패 — %s %d건 (%s)"
                    .formatted(district.getDistrictName(), pending.size(), cause);
            report.failUnexpected(reason);
            log.warn("[매물 갱신] {}", reason, cause);
            return;
        }
        report.addPriceUpdated(pending.size());
        report.addPriceChanged(priceChangedIds.size());
        refreshTargets.priceChangedPropertyIds.addAll(priceChangedIds);
    }

    /**
     * 주소 문자열을 만든다. 실거래가는 시 · 구를 코드로만 주므로 앞에 붙여 완전한 주소로 만든다.
     */
    private String composeRawAddress(SeoulDistrict district, RentTransaction transaction) {
        return "%s %s %s %s".formatted(
                        SEOUL,
                        district.getDistrictName(),
                        transaction.legalDongName() == null ? "" : transaction.legalDongName(),
                        transaction.jibun() == null ? "" : transaction.jibun())
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 실패 기록에 쓰는 한 건의 표시. 어느 구 · 어느 건물인지 알아볼 만큼만 적는다. */
    private String describe(SeoulDistrict district, RentTransaction transaction) {
        return "%s %s %s %s".formatted(
                district.getDistrictName(),
                transaction.legalDongName(),
                transaction.jibun(),
                transaction.buildingName());
    }

    /**
     * 갱신 적재가 모으는 식별자. 한 번의 {@link #refresh} 동안만 산다 — 인스턴스 간에 공유할 상태가 아니다.
     */
    private static final class RefreshTargets {
        private final List<Long> newPropertyIds = new ArrayList<>();
        private final List<Long> priceChangedPropertyIds = new ArrayList<>();
    }

    private LocalDate basePriceDate(LocalDate baseDate) {
        return baseDate == null ? LocalDate.now() : baseDate;
    }

    /**
     * 실거래가 서비스 구분을 매물 유형 코드로 옮긴다. 외부의 구분이 도메인 값으로 새지 않게 한다.
     */
    private PropertyType toPropertyType(RentBuildingType buildingType) {
        return switch (buildingType) {
            case APARTMENT -> PropertyType.APARTMENT;
            case OFFICETEL -> PropertyType.OFFICETEL;
        };
    }
}
