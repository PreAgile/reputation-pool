package io.github.preagile.reputationpool.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PoolEventTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("baemin");
    private static final Instant AT = Instant.parse("2026-07-07T00:00:00Z");

    @Test
    void resourceCooledHoldsItsFields() {
        var until = AT.plusSeconds(3600);
        var event = new PoolEvent.ResourceCooled(RID, CTX, AT, until, FailureType.BLOCKED);
        assertThat(event.resource()).isEqualTo(RID);
        assertThat(event.context()).isEqualTo(CTX);
        assertThat(event.at()).isEqualTo(AT);
        assertThat(event.until()).isEqualTo(until);
        assertThat(event.cause()).isEqualTo(FailureType.BLOCKED);
    }

    @Test
    void resourceRecoveredHoldsItsFields() {
        var event = new PoolEvent.ResourceRecovered(RID, CTX, AT);
        assertThat(event.resource()).isEqualTo(RID);
        assertThat(event.context()).isEqualTo(CTX);
        assertThat(event.at()).isEqualTo(AT);
    }

    @Test
    void occurrenceTimeIsReadableWithoutDiscriminatingCase() {
        // the common at() accessor lets any event's timestamp be read uniformly
        PoolEvent cooled = new PoolEvent.ResourceCooled(RID, CTX, AT, AT.plusSeconds(60), FailureType.TIMEOUT);
        PoolEvent recovered = new PoolEvent.ResourceRecovered(RID, CTX, AT);
        assertThat(cooled.at()).isEqualTo(AT);
        assertThat(recovered.at()).isEqualTo(AT);
    }

    @Test
    void exhaustiveSwitchNeedsNoDefault() {
        // compiles only because PoolEvent is sealed: the two cases are the whole set
        assertThat(describe(new PoolEvent.ResourceCooled(RID, CTX, AT, AT.plusSeconds(60), FailureType.SLOW)))
                .isEqualTo("cooled");
        assertThat(describe(new PoolEvent.ResourceRecovered(RID, CTX, AT))).isEqualTo("recovered");
    }

    private static String describe(PoolEvent event) {
        return switch (event) {
            case PoolEvent.ResourceCooled c -> "cooled";
            case PoolEvent.ResourceRecovered r -> "recovered";
        };
    }

    @Test
    void resourceCooledRejectsNullComponents() {
        var until = AT.plusSeconds(3600);
        assertThatThrownBy(() -> new PoolEvent.ResourceCooled(null, CTX, AT, until, FailureType.BLOCKED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resource");
        assertThatThrownBy(() -> new PoolEvent.ResourceCooled(RID, null, AT, until, FailureType.BLOCKED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
        assertThatThrownBy(() -> new PoolEvent.ResourceCooled(RID, CTX, null, until, FailureType.BLOCKED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("at");
        assertThatThrownBy(() -> new PoolEvent.ResourceCooled(RID, CTX, AT, null, FailureType.BLOCKED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("until");
        assertThatThrownBy(() -> new PoolEvent.ResourceCooled(RID, CTX, AT, until, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cause");
    }

    @Test
    void resourceRecoveredRejectsNullComponents() {
        assertThatThrownBy(() -> new PoolEvent.ResourceRecovered(null, CTX, AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resource");
        assertThatThrownBy(() -> new PoolEvent.ResourceRecovered(RID, null, AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
        assertThatThrownBy(() -> new PoolEvent.ResourceRecovered(RID, CTX, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("at");
    }
}
