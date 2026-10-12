package br.com.app.temp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel channel() {
        String target = System.getenv().getOrDefault("GRPC_TARGET", "dns:///backend:9090");
        String lbPolicy = System.getenv().getOrDefault("LB_POLICY", "round_robin");

        return ManagedChannelBuilder.forTarget(target)   // "dns:///" garante o resolvedor DNS
                .defaultLoadBalancingPolicy(lbPolicy)    // round_robin ou pick_first
                .usePlaintext()
                .build();
    }
}