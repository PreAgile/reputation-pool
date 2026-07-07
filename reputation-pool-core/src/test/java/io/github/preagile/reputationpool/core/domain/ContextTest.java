package io.github.preagile.reputationpool.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContextTest {

    @Test
    void globalIsTheReservedBaseAxis() {
        assertThat(Context.GLOBAL.value()).isEqualTo("*");
    }

    @Test
    void distinctPlatformsAreNotEqual() {
        assertThat(new Context("baemin")).isNotEqualTo(new Context("yogiyo"));
    }

    @Test
    void rejectsNullOrBlankValue() {
        assertThatThrownBy(() -> new Context(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Context("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Context("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
