package br.com.app.temp;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

public class GrpcBackend {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
        long maxAgeSeconds = Long.parseLong(System.getenv().getOrDefault("MAX_CONNECTION_AGE_SECONDS", "0"));
        String hostname = InetAddress.getLocalHost().getHostName();

        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .addService(new EchoGrpc.EchoImplBase() {
                    @Override
                    public void whoami(WhoamiRequest req, StreamObserver<WhoamiReply> out) {
                        out.onNext(WhoamiReply.newBuilder().setHostname(hostname).build());
                        out.onCompleted();
                    }
                });

        if (maxAgeSeconds > 0) {
            // Servidor envia GOAWAY após esse tempo: força o cliente a reconectar e re-resolver o DNS
            builder.maxConnectionAge(maxAgeSeconds, TimeUnit.SECONDS)
                    .maxConnectionAgeGrace(2, TimeUnit.SECONDS);
        }

        Server server = builder.build().start();
        System.out.printf("gRPC backend %s na porta %d (maxConnectionAge=%ds)%n", hostname, port, maxAgeSeconds);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
