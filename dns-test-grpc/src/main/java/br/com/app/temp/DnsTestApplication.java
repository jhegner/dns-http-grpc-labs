package br.com.app.temp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

@SpringBootApplication
public class DnsTestApplication {

    public static void main(String[] args) throws Exception {
        // Mesma imagem, dois papéis
        if ("server".equalsIgnoreCase(System.getenv("MODE"))) {
            GrpcBackend.main(args);
            return;
        }

        String ttl = System.getenv().getOrDefault("DNS_TTL", "5");

        // Resolvedor DNS do gRPC lê a propriedade de SISTEMA
        System.setProperty("networkaddress.cache.ttl", ttl);
        // InetAddress (JDK) lê a propriedade de SEGURANÇA
        Security.setProperty("networkaddress.cache.ttl", ttl);
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        SpringApplication.run(DnsTestApplication.class, args);
    }
}