package moe.maika.fmteamhundo.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class StatsWidgetViewIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void statsWidgetRouteIsAnonymousVaadinPage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:%d/widgets/stats/team/1?unbuyables=false&bewds=false&dark_mode=true".formatted(port)))
            .GET()
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(contentType -> assertThat(contentType).contains("text/html"));
        assertThat(response.body()).contains("<div id=\"outlet\"></div>");
    }
}
