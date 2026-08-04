package run.halo.traffic.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IpRuleMatcherTest {
    @Test
    void matchesExactAndCidrRulesForIpv4AndIpv6() {
        String rules = "127.0.0.1, 203.0.113.0/24\n2001:db8::/32";

        assertTrue(IpRuleMatcher.matches("127.0.0.1", rules));
        assertTrue(IpRuleMatcher.matches("203.0.113.42", rules));
        assertTrue(IpRuleMatcher.matches("2001:db8::8", rules));
        assertFalse(IpRuleMatcher.matches("203.0.114.42", rules));
        assertFalse(IpRuleMatcher.matches("2001:db9::8", rules));
    }

    @Test
    void ignoresInvalidRules() {
        assertFalse(IpRuleMatcher.matches("203.0.113.8", "bad-value 203.0.113.0/99"));
    }
}
