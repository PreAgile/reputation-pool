/**
 * reputation-pool-core — 순수 Java 평판 판정 엔진.
 *
 * <p>이 패키지는 프레임워크·네트워크·저장소에 의존하지 않는다(JDK only). 판정은 순수 함수로,
 * 상태는 불변 레코드로 표현하며, 바깥 세계(시간·저장·프로브·관측)와의 접점은 {@code port}
 * 하위 패키지의 인터페이스로만 노출한다. 이 순수성은 ArchUnit 규칙으로 빌드에서 강제된다.
 */
package io.github.preagile.reputationpool.core;
