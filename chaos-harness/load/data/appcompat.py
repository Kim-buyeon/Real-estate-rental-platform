"""앱 규칙을 그대로 옮긴 함수 (#376). 생성기가 만드는 값이 앱 적재기가 만들 값과 같아야 한다.

옮긴 원본 — 바뀌면 여기도 바꾸고 앱 값 대조(verify_against_db)를 다시 돈다.
- AreaBand.of                     backend/.../domain/property/enums/AreaBand.java
- ContractTypeClassifier.classify backend/.../domain/property/calculator/ContractTypeClassifier.java
- LandlordNameGenerator           backend/.../domain/property/calculator/LandlordNameGenerator.java
- PropertyNaturalKey (면적 scale 2, HALF_UP)
- MarketPriceCalculator           backend/.../domain/property/calculator/MarketPriceCalculator.java
- LedgerLookupKey.of              backend/.../domain/property/vo/LedgerLookupKey.java
- composeRawAddress               PropertyLoadService.composeRawAddress
"""
from decimal import Decimal, ROUND_HALF_UP

MASK = (1 << 64) - 1
SEOUL = '서울특별시'

# ---- 면적 · 계약 유형 -------------------------------------------------------------

AREA_BANDS = [('UNDER_40', None, Decimal('40')), ('FROM_40_TO_60', Decimal('40'), Decimal('60')),
              ('FROM_60_TO_85', Decimal('60'), Decimal('85')), ('FROM_85_TO_135', Decimal('85'), Decimal('135')),
              ('OVER_135', Decimal('135'), None)]


def area_band(area: Decimal) -> str:
    for name, lo, hi in AREA_BANDS:
        if (lo is None or area >= lo) and (hi is None or area < hi):
            return name
    raise ValueError(area)


def area_scale2(area: Decimal) -> Decimal:
    return area.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)


SEMI_DEPOSIT_MONTHS = 240


def contract_type(deposit: int, monthly_rent: int) -> str:
    if monthly_rent <= 0:
        return 'DEPOSIT_ONLY'
    if deposit > monthly_rent * SEMI_DEPOSIT_MONTHS:
        return 'SEMI_DEPOSIT'
    return 'MONTHLY_RENT'


# ---- 임대인명 ---------------------------------------------------------------------

LANDLORD_SEED = 0x9E3779B97F4A7C15
MISMATCH_SEED = 0xBF58476D1CE4E5B9
FNV_OFFSET_BASIS = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MIX_HIGH = 0xFF51AFD7ED558CCD
MIX_LOW = 0xC4CEB9FE1A85EC53
FAMILY_NAMES = ['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임']
GIVEN_NAMES = ['민준', '서연', '도윤', '지우', '예준', '하은', '시우', '지민', '주원', '수아', '건우', '유진', '현우', '다인', '준서']


def _signed(v):
    v &= MASK
    return v - (1 << 64) if v >> 63 else v


def _mix(v):
    v &= MASK
    v ^= v >> 33
    v = (v * MIX_HIGH) & MASK
    v ^= v >> 33
    v = (v * MIX_LOW) & MASK
    v ^= v >> 33
    return v


def _fold(text):
    h = FNV_OFFSET_BASIS
    for ch in text.encode('utf-16-be').decode('utf-16-be'):
        # 자바 String.charAt 은 UTF-16 코드 단위다. 한글 · ASCII 는 BMP 라 코드 포인트와 같다
        h ^= ord(ch)
        h = (h * FNV_PRIME) & MASK
    return h


def natural_key_text(address, area: Decimal, floor, deposit, monthly_rent):
    floor_text = 'null' if floor is None else str(floor)
    return '%s|%s|%s|%s|%s' % (address, area_scale2(area), floor_text, deposit, monthly_rent)


def _derive(key_text, seed):
    return _mix(_fold(key_text) + seed)


def landlord_name(key_text):
    h = _derive(key_text, LANDLORD_SEED)
    family = FAMILY_NAMES[_signed(h) % len(FAMILY_NAMES)]
    given = GIVEN_NAMES[_signed(_mix(h)) % len(GIVEN_NAMES)]
    return family + given


def other_name(name, key_text):
    """불일치 매물의 등기 소유자 — 임대인명과 다른 이름. Mock 의 이름 풀 대신 같은 성 · 이름 풀에서 결정적으로 고른다."""
    h = _mix(_derive(key_text, MISMATCH_SEED))
    for step in range(len(FAMILY_NAMES) * len(GIVEN_NAMES)):
        v = _signed(_mix(h + step))
        candidate = FAMILY_NAMES[v % len(FAMILY_NAMES)] + GIVEN_NAMES[_signed(_mix(v)) % len(GIVEN_NAMES)]
        if candidate != name:
            return candidate
    raise AssertionError(name)


def is_intentional_mismatch(key_text):
    return _signed(_derive(key_text, MISMATCH_SEED)) % 100 < 20


# ---- 주소 · 대장 조회 키 ----------------------------------------------------------

def jibun_text(bon: str, bu: str) -> str:
    """서울시 파일 본번 · 부번(4자리) → 실거래 API 의 jibun 표기("741", "68-19")."""
    main, sub = int(bon), int(bu or 0)
    return str(main) if sub == 0 else '%d-%d' % (main, sub)


def raw_address(district_name, legal_dong_name, jibun):
    return ' '.join(' '.join([SEOUL, district_name, legal_dong_name or '', jibun or '']).split())


def ledger_key(sigungu_code, legal_dong_code10, jibun):
    """LedgerLookupKey.of — (sigungu, bjdong 5자리, bun 4자리, ji 4자리) 또는 None."""
    if not sigungu_code or not legal_dong_code10 or not jibun:
        return None
    s, d = sigungu_code.strip(), legal_dong_code10.strip()
    if len(s) != 5 or not s.isdigit() or len(d) != 10 or not d.isdigit() or not d.startswith(s):
        return None
    parts = jibun.strip().split('-')
    if not parts[0].isdigit() or len(parts) > 2 or (len(parts) == 2 and not parts[1].isdigit()):
        return None
    sub = parts[1] if len(parts) == 2 else '0'
    return s, d[5:], '%04d' % int(parts[0]), '%04d' % int(sub)


# ---- 시세 -------------------------------------------------------------------------

def median(values):
    s = sorted(values)
    n = len(s)
    m = n // 2
    return s[m] if n % 2 == 1 else (s[m - 1] + s[m]) // 2


class MarketPrices:
    """자치구 하나의 매매 표본으로 만든 시세표. find 가 None 이면 앱은 그 매물을 버린다."""

    def __init__(self, sales):
        self.by_dong, self.by_district = {}, {}
        for s in sales:  # (ptype, dong, area Decimal, amount_won, contract_date, cancelled)
            ptype, dong, area, amount, cdate, cancelled = s
            if cancelled or area is None or amount is None or cdate is None:
                continue
            band = area_band(area)
            for table, key in ((self.by_dong, (ptype, dong, band)), (self.by_district, (ptype, band))):
                entry = table.setdefault(key, [[], None])
                entry[0].append(amount)
                if entry[1] is None or cdate > entry[1]:
                    entry[1] = cdate
        self._cache = {}

    def find(self, ptype, dong, area: Decimal):
        band = area_band(area)
        key = (ptype, dong, band)
        if key in self._cache:
            return self._cache[key]
        entry = self.by_dong.get(key) or self.by_district.get((ptype, band))
        result = None if entry is None else (median(entry[0]), entry[1])
        self._cache[key] = result
        return result
