package org.jeecg.modules.device.service.webrtc;

import org.jeecg.modules.device.config.WebRtcProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class TurnRouterClientTest {

    private MockRestServiceServer server;
    private TurnRouterClient client;

    @BeforeEach
    void setUp() {
        WebRtcProperties properties = new WebRtcProperties();
        WebRtcProperties.TurnRouter router = new WebRtcProperties.TurnRouter();
        router.setBaseUrl("http://turn-router.test");
        properties.setTurnRouter(router);

        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new TurnRouterClient(properties, restTemplate);
    }

    @AfterEach
    void verify() {
        server.verify();
    }

    @Test
    void routeReturnsSignalKeyFromResponse() {
        String body = """
                {
                  "success": true,
                  "serverId": "47.102.207.121",
                  "serverIp": "47.102.207.121",
                  "serverPort": 3479,
                  "serverName": "上海TURN服务器1",
                  "signalKey": "room-key-from-router",
                  "message": ""
                }
                """;
        server.expect(requestTo("http://turn-router.test/api/v1/route/turn"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"callId":"room-1","robotProvince":"江苏省","robotCity":"常州市","browserProvince":"北京市","browserCity":"北京市"}
                        """))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TurnRouteResult result = client.route(
                "room-1", "江苏省", "常州市", "北京市", "北京市");

        assertEquals("47.102.207.121", result.getServerIp());
        assertEquals(3479, result.getServerPort());
        assertEquals("room-key-from-router", result.getSignalKey());
    }

    @Test
    void routeFailsWhenSignalKeyMissing() {
        String body = """
                {
                  "success": true,
                  "serverIp": "47.102.207.121",
                  "serverPort": 3479,
                  "signalKey": ""
                }
                """;
        server.expect(requestTo("http://turn-router.test/api/v1/route/turn"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThrows(RuntimeException.class, () ->
                client.route("room-1", "江苏省", null, "北京市", null));
    }
}
