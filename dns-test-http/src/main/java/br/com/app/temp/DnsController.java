package br.com.app.temp;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class DnsController {

    private static final String HOST = "backend";

    // SimpleClientHttpRequestFactory = HttpURLConnection, usa o cache do InetAddress
    private final RestClient client = RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();

    @GetMapping("/call")
    public Map<String, Object> call() throws Exception {
        List<String> ips = Arrays.stream(InetAddress.getAllByName(HOST))
                .map(InetAddress::getHostAddress)
                .sorted()
                .toList();

        String body = client.get()
                .uri("http://" + HOST + "/")
                .header("Connection", "close")
                .retrieve()
                .body(String.class);

        String hostname = body.lines()
                .filter(l -> l.startsWith("Hostname:"))
                .findFirst().orElse("?");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ipsResolvidosPelaJvm", ips);
        result.put("respondeu", hostname);
        return result;
    }
}