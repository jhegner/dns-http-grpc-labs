package br.com.app.temp;

import java.security.Security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication 
public class DnsTestApplication {

        public static void main(String[] args) {
        String ttl = System.getenv().getOrDefault("DNS_TTL", "5");

        // Cache de resultados positivos (segundos). -1 = para sempre
        Security.setProperty("networkaddress.cache.ttl", ttl);
        // Cache de falhas (NXDOMAIN, etc.)
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        // Evita reaproveitar conexões TCP keep-alive, que "colariam" no IP antigo
        System.setProperty("http.keepAlive", "false");

        SpringApplication.run(DnsTestApplication.class, args);
    }

}
