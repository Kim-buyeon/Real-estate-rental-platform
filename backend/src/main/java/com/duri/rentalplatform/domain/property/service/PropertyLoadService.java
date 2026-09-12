package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.domain.property.calculator.ContractTypeClassifier;
import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.calculator.MarketPriceCalculator;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.SeoulDistrict;
import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.Coordinates;
import com.duri.rentalplatform.external.address.GeocodeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 매물 초기 적재. 실거래가를 모아 주소를 정규화하고 좌표를 확보한 뒤 매물로 저장한다 —
 * 데이터 적재 설계서 1.4.
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
 *   <li>한 구 · 한 달의 조회가 실패해도 나머지 달로 넘어간다.</li>
 *   <li>필수 값이 빠진 실거래 한 건은 시세 표본에 넣기 전에 걸러 낸다 — 면적이나 보증금이 없으면
 *       면적대 분류와 금액 계산에서 예외가 난다.</li>
 *   <li>한 건을 옮기다 무엇이 나든 그 건만 버린다. 예상하지 못한 예외까지 잡는 이유는, 한 건의
 *       자료 이상이 25개 구 전체를 멈추게 두지 않기 위해서다.</li>
 *   <li>저장 한 덩어리가 실패하거나 한 자치구가 통째로 멈춰도 다음 자치구는 돈다.</li>
 * </ol>
 *
 * <p>무엇을 몇 건 버렸는지는 {@link PropertyLoadReport} 에 남고 실행이 끝난 뒤 한 번에 출력된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyLoadService {

    /** 한 트랜잭션에 넣는 건수. 한 구를 통째로 한 트랜잭션에 담으면 커넥션을 오래 붙든다. */
    private static final int SAVE_CHUNK_SIZE = 500;

    private static final String SEOUL = "서울특별시";

    private final RentTransactionClient rentTransactionClient;
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
        List<YearMonth> targetMonths = recentMonths(months);

        for (SeoulDistrict district : SeoulDistrict.values()) {
            try {
                loadDistrict(district, targetMonths, report);
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
     */
    private void loadDistrict(SeoulDistrict district, List<YearMonth> targetMonths,
                              PropertyLoadReport report) {
        List<RentTransaction> fetched = fetchTransactions(district, targetMonths, report);
        report.addFetched(fetched.size());

        List<RentTransaction> transactions = filterUsable(fetched, district, report);
        if (transactions.isEmpty()) {
            return;
        }

        MarketPriceCalculator marketPrices = MarketPriceCalculator.from(transactions);
        Set<PropertyNaturalKey> seenKeys =
                new HashSet<>(propertyLoadWriter.findLoadedNaturalKeys(district.getDistrictName()));

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
                continue;
            }
            pending.add(registration);

            if (pending.size() >= SAVE_CHUNK_SIZE) {
                report.addSaved(saveChunk(pending, district, report));
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            report.addSaved(saveChunk(pending, district, report));
        }
    }

    /**
     * 한 자치구의 대상 기간 실거래를 모은다. 유형별로 서비스가 다르므로 유형 수 × 달 수만큼 호출한다.
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
     * 필수 값이 빠진 건을 걸러 낸다.
     *
     * <p>{@code RentTransaction} 은 제공처가 비워 보내는 필드를 그대로 받는 레코드라 null 을 막지
     * 않는다. 걸러 내지 않으면 면적대 분류와 금액 계산에서 예외가 나고, 그 예외는 한 건이 아니라
     * 시세표를 만드는 단계에서 터져 자치구 전체를 멈춘다.
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
                marketPrices.find(transaction.legalDongName(), transaction.areaSqm());
        if (marketPrice.isEmpty()) {
            // 시세는 깡통전세 판정 기준금액의 밑값이자 전세가율의 분모다. 표본이 없으면 값을 지어내지
            // 않고 그 매물을 버린다.
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
                coordinates.get().longitude());
    }

    /**
     * 한 덩어리를 저장한다. 실패하면 그 덩어리만 버리고 다음 덩어리로 넘어간다.
     *
     * <p>여기서 막지 않으면 덩어리 하나의 저장 실패가 자치구 반복문 밖으로 나가, 그때까지의 건수와
     * 실패 목록이 출력되기 전에 실행이 끝난다.
     */
    private int saveChunk(List<PropertyRegistration> pending, SeoulDistrict district,
                          PropertyLoadReport report) {
        try {
            return propertyLoadWriter.saveAll(pending);
        } catch (RuntimeException cause) {
            String reason = "저장 실패 — %s %d건 (%s)"
                    .formatted(district.getDistrictName(), pending.size(), cause);
            report.failUnexpected(reason);
            log.warn("[매물 적재] {}", reason, cause);
            return 0;
        }
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
