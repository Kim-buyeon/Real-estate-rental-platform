package com.duri.rentalplatform.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * 가입 비밀번호의 길이 정책. 최소 8자 · 최대 64자 · UTF-8 72바이트를 본다.
 * 문자 종류 조합 규칙은 두지 않는다 — NIST SP 800-63B가 조합 규칙을 부과하지 말라고 명시한다.
 *
 * <p>문자 수 상한과 별개로 바이트 상한을 두는 이유: bcrypt는 72바이트까지만 입력으로 받는다.
 * Spring Security는 CVE-2025-22228 수정 이후 이 한계를 넘는 새 비밀번호를 조용히 자르지 않고
 * {@link IllegalArgumentException}을 던진다. 문자 수만 검사하면 한글 비밀번호가 64자 이내여도
 * UTF-8로 192바이트가 되어 인코딩 단계에서 예외가 나고, 전역 처리기가 그것을 500으로 바꾼다.
 * 사용자는 400과 위반 필드를 받아야 하므로 바이트 길이를 검증 단계에서 함께 본다.
 * 바이트 검사를 지우면 이 경로가 되살아난다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordPolicy.Validator.class)
public @interface PasswordPolicy {

    String message() default "비밀번호는 8자 이상 64자 이하이며 UTF-8 기준 72바이트를 넘지 않아야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PasswordPolicy, String> {

        private static final int MINIMUM_LENGTH = 8;
        private static final int MAXIMUM_LENGTH = 64;
        private static final int MAXIMUM_UTF8_BYTE_LENGTH = 72;

        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            // 필수 여부는 @NotBlank가 판단한다. 한 제약이 둘을 판단하면 어느 것 때문에 실패했는지 흐려진다.
            if (password == null) {
                return true;
            }
            if (password.length() < MINIMUM_LENGTH) {
                return false;
            }
            if (password.length() > MAXIMUM_LENGTH) {
                return false;
            }
            return password.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_UTF8_BYTE_LENGTH;
        }
    }
}
