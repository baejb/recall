package com.recall.settings.service.entity;

/**
 * 테스트용 {@link ModelSetting} 생성기.
 *
 * <p><b>왜 테스트 트리에 있나</b> — {@code ModelSetting} 의 인자 없는 생성자는 JPA 전용이라 {@code protected} 이고, <b>프로덕션
 * 코드는 이 엔티티를 한 번도 생성하지 않는다</b>(행은 Flyway 마이그레이션이 넣고 앱은 읽기·수정만 한다). 유일한 생성자 사용처가 테스트라는 뜻이므로, 생성자를
 * {@code public} 으로 넓히면 <b>테스트 편의를 위해 프로덕션 API를 여는</b> 셈이 된다.
 *
 * <p>대신 이 픽스처를 엔티티와 <b>같은 패키지</b>(단, 테스트 소스 루트)에 두어 {@code protected} 생성자에 정당하게 닿는다. 프로덕션 가시성은
 * 그대로다.
 */
public final class ModelSettingFixture {

    private ModelSettingFixture() {}

    /** 모든 필드가 비어 있는 행. 호출자가 필요한 필드만 세터로 채운다. */
    public static ModelSetting empty() {
        return new ModelSetting();
    }
}
