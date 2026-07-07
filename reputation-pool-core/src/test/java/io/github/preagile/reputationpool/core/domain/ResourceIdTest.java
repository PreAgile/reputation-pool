package io.github.preagile.reputationpool.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResourceIdTest {

    @Test
    void holdsKindAndValue() {
        var id = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
        assertThat(id.kind()).isEqualTo(ResourceKind.PROXY);
        assertThat(id.value()).isEqualTo("1.2.3.4:8080");
    }

    @Test
    void sameKindAndValueAreEqual() {
        assertThat(new ResourceId(ResourceKind.ACCOUNT, "acc-1"))
                .isEqualTo(new ResourceId(ResourceKind.ACCOUNT, "acc-1"));
    }

    @Test
    void kindNamespacesValue() {
        // same string, different kind → distinct ids (no key collision across kinds)
        assertThat(new ResourceId(ResourceKind.PROXY, "x")).isNotEqualTo(new ResourceId(ResourceKind.ACCOUNT, "x"));
    }

    @Test
    void rejectsNullKind() {
        assertThatThrownBy(() -> new ResourceId(null, "v")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullOrBlankValue() {
        assertThatThrownBy(() -> new ResourceId(ResourceKind.PROXY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceId(ResourceKind.PROXY, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
