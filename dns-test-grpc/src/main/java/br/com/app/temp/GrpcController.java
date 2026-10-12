package br.com.app.temp;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
public class GrpcController {

    private final EchoGrpc.EchoBlockingStub stub;

    public GrpcController(ManagedChannel channel) {
        this.stub = EchoGrpc.newBlockingStub(channel);
    }

    @GetMapping("/call")
    public Map<String, Object> call(@RequestParam(defaultValue = "10") int n) {
        Map<String, Integer> distribuicao = new TreeMap<>();
        int erros = 0;
        String ultimoErro = null;

        for (int i = 0; i < n; i++) {
            try {
                String host = stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                        .whoami(WhoamiRequest.getDefaultInstance())
                        .getHostname();
                distribuicao.merge(host, 1, Integer::sum);
            } catch (StatusRuntimeException e) {
                erros++;
                ultimoErro = e.getStatus().getCode().name();
            }
        }

        // Visão do JDK (cache separado do cache do gRPC), só para comparação
        List<String> ipsJdk;
        try {
            ipsJdk = Arrays.stream(InetAddress.getAllByName("backend"))
                    .map(InetAddress::getHostAddress).sorted().toList();
        } catch (Exception e) {
            ipsJdk = List.of("erro: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distribuicao", distribuicao);
        result.put("erros", erros);
        if (ultimoErro != null) result.put("ultimoErro", ultimoErro);
        result.put("ipsVisaoJdk", ipsJdk);
        return result;
    }
}