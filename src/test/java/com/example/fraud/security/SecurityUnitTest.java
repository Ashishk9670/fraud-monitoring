package com.example.fraud.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class SecurityUnitTest {
    @Test
    void validatesKeysAndRejectsBlankOrUnknown() {
        ApiKeys keys = new ApiKeys(List.of("k1", " k2 ", ""));
        assertThat(keys.isValid("k1")).isTrue();
        assertThat(keys.isValid("k2")).isTrue();
        assertThat(keys.isValid("k3")).isFalse();
        assertThat(keys.isValid("")).isFalse();
        assertThat(keys.isValid(null)).isFalse();
    }

    @Test
    void extractsKeyFromHeaderOrBearer() {
        MockHttpServletRequest a = new MockHttpServletRequest();
        a.addHeader("X-API-Key", "abc");
        assertThat(ApiKeys.extract(a)).isEqualTo("abc");
        MockHttpServletRequest b = new MockHttpServletRequest();
        b.addHeader("Authorization", "bearer xyz");
        assertThat(ApiKeys.extract(b)).isEqualTo("xyz");
        assertThat(ApiKeys.extract(new MockHttpServletRequest())).isNull();
    }

    @Test
    void sizeFilterRejectsDeclaredOversizeBodies() throws Exception {
        var filter = new RequestSizeLimitFilter(new SecurityProperties(List.of("k"), DataSize.ofBytes(10), 5, 8));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/transactions");
        req.setContent(new byte[11]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(413);

        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/api/transactions");
        ok.setContent(new byte[10]);
        MockHttpServletResponse okRes = new MockHttpServletResponse();
        filter.doFilter(ok, okRes, new MockFilterChain());
        assertThat(okRes.getStatus()).isEqualTo(200);
    }
}
