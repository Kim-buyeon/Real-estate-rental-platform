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
 * 두지 않는다. 저장은 {@link PropertyLoadWriter} 가 맡고 여기에는 트랜잭션이 없다 — <b>외부 호출을
 * 트랜잭션 안에 두지 않기 위해서</b>다.
 *
 * <p><b>실패를 다루는 방식</b> — 한 구 · 한 달의 조회가 실패해도 다음으로 넘어간다. 한 건의 주소
 * 정규화가 실패해도 그 건만 건너뛴다. 일부 실패가 전체 적재를 중단시키지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyLoadService {

    private final RentTransactionClient rentTransactionClient;
    private final AddressNormalizeClient addressNormalizeClient;
    private final GeocodeClient geocodeClient;
    private final PropertyLoadWriter propertyLoadWriter;

    /** 한 트랜잭션에 넣는 건수. 한 구를 통째로 한 트랜잭션에 담으면 커넥션을 오래 붙든다. */
    private static final int SAVE_CHUNK_SIZE = 500;

    private static final String SEOUL = "서울특별시";

    /**
     * 서울 25개 자치구의 최근 {@code months} 개월 실거래를 적재한다.
     *
     * @param months 오늘이 속한 달의 직전 달부터 거슬러 올라갈 개월 수. 이번 달은 신고분이 거의 없다
     */
    public PropertyLoadReport load(int months) {
        PropertyLoadReport report = new PropertyLoadReport();
        List<YearMonth> targetMonths = recentMonths(months);

        for (SeoulDistrict district : SeoulDistrict.values()) {
            loadDistrict(district, targetMonths, report);
            log.info("[매물 적재] {} 완료 — {}", district.getDistrictName(), report.summary());
        }
        return report;
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
        List<RentTransaction> transactions = fetchTransactions(district, targetMonths, report);
        if (transactions.isEmpty()) {
            return;
        }
        report.addFetched(transactions.size());

        MarketPriceCalculator marketPrices = MarketPriceCalculator.from(transactions);
        Set<PropertyNaturalKey> seenKeys =
                new HashSet<>(propertyLoadWriter.findLoadedNaturalKeys(district.getDistrictName()));

        // 같은 건물 · 같은 지번이 여러 번 나온다. 구 단위 실행 동안만 사는 메모라 인스턴스 간에
        // 공유할 상태가 아니다. 이것이 없으면 같은 주소를 수십 번 외부에 묻는다.
        Map<String, Optional<NormalizedAddress>> addressMemo = new HashMap<>();
        Map<String, Optional<Coordinates>> coordinateMemo = new HashMap<>();

        List<PropertyRegistration> pending = new ArrayList<>(SAVE_CHUNK_SIZE);
        for (RentTransaction transaction : transactions) {
            PropertyRegistration registration =
                    toRegistration(transaction, district, marketPrices, addressMemo, coordinateMemo, report);
            if (registration == null) {
                continue;
            }
            if (!seenKeys.add(registration.naturalKey())) {
                report.skipDuplicate();
                continue;
            }
            pending.add(registration);

            if (pending.size() >= SAVE_CHUNK_SIZE) {
                report.addSaved(propertyLoadWriter.saveAll(pending));
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            report.addSaved(propertyLoadWriter.saveAll(pending));
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
            // 시세는 전세가율의 분모다. 표본이 없으면 값을 지어내지 않고 그 매물을 버린다.
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
