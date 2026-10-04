package com.secret_message.secret_message_app.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The production switch is {@code app.env} (env {@code APP_ENV}), the same one
 * IdempotencyKeyVault and docker-entrypoint.sh use — not Spring profiles.
 */
class ClientIpFilterTest {

    private static MockHttpServletRequest unresolvableIpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/messages");
        request.setRemoteAddr("0.0.0.0");
        return request;
    }

    @Test
    void productionRejectsUnresolvableIp() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ClientIpFilter("production").doFilter(unresolvableIpRequest(), response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest(), "request must not reach the rest of the chain");
    }

    @Test
    void productionMatchIsCaseInsensitive() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ClientIpFilter("PRODUCTION").doFilter(unresolvableIpRequest(), response, new MockFilterChain());

        assertEquals(400, response.getStatus());
    }

    @Test
    void developmentFallsBackToLoopback() throws Exception {
        MockHttpServletRequest request = unresolvableIpRequest();
        MockFilterChain chain = new MockFilterChain();

        new ClientIpFilter("development").doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals("127.0.0.1", request.getAttribute(ClientIpFilter.CLIENT_IP_ATTRIBUTE));
    }

    @Test
    void resolvedIpPassesThroughInProduction() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/messages");
        request.setRemoteAddr("203.0.113.7");
        MockFilterChain chain = new MockFilterChain();

        new ClientIpFilter("production").doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals("203.0.113.7", request.getAttribute(ClientIpFilter.CLIENT_IP_ATTRIBUTE));
    }
}
