package com.netflix.eureka2.client.transport;

import com.netflix.eureka2.client.resolver.ServerResolver;
import com.netflix.eureka2.transport.MessageConnection;
import com.netflix.eureka2.transport.TransportClient;
import com.netflix.eureka2.transport.base.BaseMessageConnection;
import com.netflix.eureka2.transport.base.HeartBeatConnection;
import com.netflix.eureka2.metric.MessageConnectionMetrics;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.client.RxClient;
import io.reactivex.netty.pipeline.PipelineConfigurator;
import rx.Observable;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Convenience base implementation for {@link com.netflix.eureka2.transport.TransportClient} that reads the server list from a {@link ServerResolver}
 *
 * @author Nitesh Kant
 */
public abstract class ResolverBasedTransportClient implements TransportClient {

    private final ServerResolver resolver;
    private final PipelineConfigurator<Object, Object> pipelineConfigurator;
    private final MessageConnectionMetrics metrics;
    private ConcurrentHashMap<ServerResolver.Server, RxClient<Object, Object>> clients;

    protected ResolverBasedTransportClient(ServerResolver resolver,
                                           PipelineConfigurator<Object, Object> pipelineConfigurator,
                                           MessageConnectionMetrics metrics) {
        this.resolver = resolver;
        this.pipelineConfigurator = pipelineConfigurator;
        this.metrics = metrics;
        clients = new ConcurrentHashMap<>();
    }

    public long getHeartbeatIntervalMillis() {
        return HeartBeatConnection.DEFAULT_HEARTBEAT_INTERVAL_MILLIS;
    }

    @Override
    public Observable<MessageConnection> connect() {
        return resolver.resolve()
                .take(1)
                .map(new Func1<ServerResolver.Server, RxClient<Object, Object>>() {
                    @Override
                    public RxClient<Object, Object> call(ServerResolver.Server server) {
                        // This should be invoked from a single thread.
                        RxClient<Object, Object> client = clients.get(server);
                        if (null == client) {
                            client = RxNetty.createTcpClient(server.getHost(), server.getPort(),
                                    pipelineConfigurator);
                            clients.put(server, client);
                        }
                        return client;
                    }
                })
                .flatMap(new Func1<RxClient<Object, Object>, Observable<MessageConnection>>() {
                    @Override
                    public Observable<MessageConnection> call(RxClient<Object, Object> client) {
                        return client.connect()
                                .map(new Func1<ObservableConnection<Object, Object>, MessageConnection>() {
                                    @Override
                                    public MessageConnection call(
                                            ObservableConnection<Object, Object> conn) {
                                        return new HeartBeatConnection(
                                                new BaseMessageConnection("client", conn, metrics),
                                                getHeartbeatIntervalMillis(), 3, Schedulers.computation()
                                        );
                                    }
                                });
                    }
                })
                .retry(1); // TODO: Better retry strategy?
    }

    @Override
    public void shutdown() {
        for (RxClient<Object, Object> client : clients.values()) {
            client.shutdown();
        }
    }
}