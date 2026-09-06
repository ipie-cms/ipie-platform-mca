package in.gov.ipie.common.utils.network;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkUtilsTest {

    @Test
    void extractsTheOriginalClientFromAForwardedForChain() {
        assertThat(NetworkUtils.firstForwardedFor("203.0.113.5, 10.0.0.1, 10.0.0.2")).isEqualTo("203.0.113.5");
        assertThat(NetworkUtils.firstForwardedFor("203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(NetworkUtils.firstForwardedFor(null)).isNull();
        assertThat(NetworkUtils.firstForwardedFor("")).isNull();
    }

    @Test
    void validatesIpv4Format() {
        assertThat(NetworkUtils.isValidIpv4("192.168.1.1")).isTrue();
        assertThat(NetworkUtils.isValidIpv4("255.255.255.255")).isTrue();
        assertThat(NetworkUtils.isValidIpv4("256.1.1.1")).isFalse();
        assertThat(NetworkUtils.isValidIpv4("not-an-ip")).isFalse();
    }

    @Test
    void validatesIpv6Format() {
        assertThat(NetworkUtils.isValidIpv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).isTrue();
        assertThat(NetworkUtils.isValidIpv6("::1")).isTrue();
        assertThat(NetworkUtils.isValidIpv6("192.168.1.1")).isFalse();
        assertThat(NetworkUtils.isValidIpv6("not-an-ip")).isFalse();
    }

    @Test
    void masksLastOctetOfAnIpv4Address() {
        assertThat(NetworkUtils.maskIpv4("192.168.1.42")).isEqualTo("192.168.1.***");
    }

    @Test
    void maskIpv4ReturnsInputUnchangedWhenNotAValidIpv4() {
        assertThat(NetworkUtils.maskIpv4("not-an-ip")).isEqualTo("not-an-ip");
    }
}
