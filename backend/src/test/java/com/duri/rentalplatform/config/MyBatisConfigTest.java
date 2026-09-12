package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * MyBatis 설정({@link MyBatisConfig} + {@code application.yml} 의 {@code mybatis.*})이 실제 매핑에
 * 작용하는지 검증한다.
 *
 * <p>설정값을 읽는 것만으로는 부족하다 — {@code true} 로 켜져 있다는 사실과 스네이크 컬럼이 record 생성자에
 * 카멜로 들어온다는 사실은 다른 명제다. 그래서 실제 PostgreSQL 컨테이너
 * ({@link TestcontainersConfiguration})를 띄우고 매퍼를 호출해 결과 객체를 단언한다. 매퍼는 컴파일 시점
 * 검증이 없으므로 실제 데이터베이스에 질의해야 한다(testing.md §1.1 매퍼 테스트).
 *
 * <p>도메인 매퍼와 조회 DTO 는 만들지 않는다. 아직 없는 프로덕션 코드를 테스트가 앞질러 만드는 것이 되기
 * 때문이다. 대신 이 클래스 안의 시험용 매퍼와 중첩 record 를 쓰고, 테이블도 새로 만들지 않고
 * {@code SELECT 1 AS user_id} 처럼 리터럴에 스네이크 별칭을 붙인다 — 검증 대상은 컬럼명에서 생성자 인자로
 * 가는 경로이므로 값의 출처는 무엇이든 상관없다.
 *
 * <p>시험용 매퍼는 프로덕션 스캔 대상({@code com.duri.rentalplatform.domain.*.mapper})에 없으므로
 * {@link ProbeMapperConfig} 가 {@link MapperFactoryBean} 으로 직접 등록한다. 프로덕션 스캔 패턴은
 * 건드리지 않는다.
 *
 * <p>확인하는 것은 네 가지다.
 * <ol>
 *   <li>{@code map-underscore-to-camel-case} — 여러 단어로 된 스네이크 컬럼이 카멜 인자로 들어온다.
 *   <li>{@code arg-name-based-constructor-auto-mapping} — record 컴포넌트 순서와 SELECT 컬럼 순서가
 *       달라도 이름으로 맞는다. 순서를 같게 두면 이 설정이 꺼져 있어도 통과하므로 일부러 어긋나게 둔다.
 *   <li>{@code mapper-locations} — {@code mapper/} 아래 XML 이 실제로 잡혀 statement 가 등록된다.
 *   <li>위 두 설정이 XML 매퍼 경로에서도 동일하게 적용된다.
 * </ol>
 */
@Tag("integration")
@SpringBootTest
@Import({TestcontainersConfiguration.class, MyBatisConfigTest.ProbeMapperConfig.class})
class MyBatisConfigTest {

    /** 애노테이션 매퍼가 쓰는 SELECT. 컬럼 순서는 user_id → user_name → 금액 → 플래그 고정이다. */
    private static final String PROBE_SELECT =
            """
            SELECT 1                  AS user_id,
                   '홍길동'            AS user_name,
                   1234567.89         AS existing_loan_annual_payment,
                   true               AS deposit_guarantee_eligible
            """;

    @Autowired
    SqlSessionFactory sqlSessionFactory;

    @Autowired
    AnnotationProbeMapper annotationProbeMapper;

    @Autowired
    XmlProbeMapper xmlProbeMapper;

    private Configuration mybatisConfiguration() {
        return sqlSessionFactory.getConfiguration();
    }

    @Test
    @DisplayName("SqlSessionFactory 설정에 카멜 변환이 켜져 있다")
    void underscoreToCamelCaseIsEnabled() {
        assertThat(mybatisConfiguration().isMapUnderscoreToCamelCase()).isTrue();
    }

    @Test
    @DisplayName("SqlSessionFactory 설정에 생성자 인자 이름 기반 매핑이 켜져 있다")
    void argNameBasedConstructorAutoMappingIsEnabled() {
        assertThat(mybatisConfiguration().isArgNameBasedConstructorAutoMapping()).isTrue();
    }

    @Test
    @DisplayName("여러 단어로 된 스네이크 컬럼이 record 생성자에 카멜 이름으로 들어온다")
    void multiWordSnakeColumnsMapToCamelConstructorArgs() {
        LoanProbeRow row = annotationProbeMapper.selectInColumnOrder();

        assertThat(row).isNotNull();
        assertThat(row.userId()).isEqualTo(1L);
        assertThat(row.userName()).isEqualTo("홍길동");
        // 단어 넷으로 된 컬럼. 두 단어(user_id)만으로는 변환 규칙의 반복 적용을 확인할 수 없다.
        assertThat(row.existingLoanAnnualPayment())
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("1234567.89"));
        assertThat(row.depositGuaranteeEligible()).isTrue();
    }

    @Test
    @DisplayName("record 컴포넌트 순서가 SELECT 컬럼 순서와 정반대여도 이름으로 맞는다")
    void constructorArgsAreMatchedByNameNotByColumnOrder() {
        ShuffledProbeRow row = annotationProbeMapper.selectInShuffledOrder();

        assertThat(row).isNotNull();
        // 컴포넌트 순서(플래그 → 금액 → 이름 → 식별자)는 SELECT 순서를 뒤집은 것이다.
        // 순서로 맞추면 첫 인자에 user_id 값 1 이 들어오거나 타입 변환에서 터진다.
        assertThat(row.depositGuaranteeEligible()).isTrue();
        assertThat(row.existingLoanAnnualPayment())
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("1234567.89"));
        assertThat(row.userName()).isEqualTo("홍길동");
        assertThat(row.userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("mapper/ 아래 XML 이 mapper-locations 패턴에 잡혀 statement 가 등록된다")
    void xmlUnderMapperDirectoryIsPickedUp() {
        String statementId = XmlProbeMapper.class.getName() + ".selectFromXml";

        assertThat(mybatisConfiguration().hasStatement(statementId)).isTrue();

        MappedStatement statement = mybatisConfiguration().getMappedStatement(statementId);
        // 애노테이션이 아니라 XML 파일에서 왔음을 자원 경로로 확인한다.
        // 경로는 실행 환경의 구분자를 그대로 쓴다(Windows 는 역슬래시). 구분자를 정규화한 뒤 비교한다.
        String resource = statement.getResource().replace('\\', '/');
        assertThat(resource)
                .as("statement 출처가 mapper/ 아래 XML 파일이어야 한다")
                .contains("mapper/probe/ProbeXmlMapper.xml");
    }

    @Test
    @DisplayName("XML 매퍼에서도 스네이크 컬럼이 순서 무관하게 카멜 인자로 들어온다")
    void xmlMapperMapsSnakeColumnsToCamelConstructorArgs() {
        XmlProbeRow row = xmlProbeMapper.selectFromXml();

        assertThat(row).isNotNull();
        // XML 의 SELECT 순서는 property_id → district_name → debt_ratio_threshold 이고
        // record 순서는 그 역순이다.
        assertThat(row.debtRatioThreshold())
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("0.80"));
        assertThat(row.districtName()).isEqualTo("강남구");
        assertThat(row.propertyId()).isEqualTo(11L);
    }

    /** SELECT 컬럼 순서와 같은 순서의 컴포넌트. 카멜 변환만 검증한다. */
    record LoanProbeRow(
            Long userId,
            String userName,
            BigDecimal existingLoanAnnualPayment,
            Boolean depositGuaranteeEligible) {}

    /** SELECT 컬럼 순서를 뒤집은 컴포넌트. 이름 기반 매핑이 꺼지면 통과하지 못한다. */
    record ShuffledProbeRow(
            Boolean depositGuaranteeEligible,
            BigDecimal existingLoanAnnualPayment,
            String userName,
            Long userId) {}

    /** XML statement 의 resultType. 역시 SELECT 순서를 뒤집어 둔다. */
    record XmlProbeRow(
            BigDecimal debtRatioThreshold,
            String districtName,
            Long propertyId) {}

    /**
     * 애노테이션 매퍼. 테이블을 만들지 않고 리터럴에 스네이크 별칭을 붙인다.
     *
     * <p>PostgreSQL 은 FROM 없는 SELECT 로 리터럴을 반환한다.
     */
    interface AnnotationProbeMapper {

        @Select(PROBE_SELECT)
        LoanProbeRow selectInColumnOrder();

        @Select(PROBE_SELECT)
        ShuffledProbeRow selectInShuffledOrder();
    }

    /** XML 매퍼. statement 는 {@code test/resources/mapper/probe/ProbeXmlMapper.xml} 에 있다. */
    interface XmlProbeMapper {

        XmlProbeRow selectFromXml();
    }

    /**
     * 시험용 매퍼 등록.
     *
     * <p>{@code @MapperScan} 을 쓰지 않는 이유: 프로덕션 스캔 패턴은 도메인 패키지만 보므로 이 매퍼들이
     * 걸리지 않고, 이 패키지를 스캔 대상으로 추가하면 앞으로 이 패키지에 생기는 모든 인터페이스가 조용히
     * 매퍼로 등록된다. 두 개뿐이므로 명시적으로 등록한다. 프로덕션 스캔 패턴은 바꾸지 않는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeMapperConfig {

        @Bean
        MapperFactoryBean<AnnotationProbeMapper> annotationProbeMapper(SqlSessionFactory factory) {
            MapperFactoryBean<AnnotationProbeMapper> bean =
                    new MapperFactoryBean<>(AnnotationProbeMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        MapperFactoryBean<XmlProbeMapper> xmlProbeMapper(SqlSessionFactory factory) {
            MapperFactoryBean<XmlProbeMapper> bean = new MapperFactoryBean<>(XmlProbeMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }
    }
}
