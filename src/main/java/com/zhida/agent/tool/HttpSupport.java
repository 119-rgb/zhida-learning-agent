package com.zhida.agent.tool;

import org.springframework.web.client.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.util.function.Supplier;

public final class HttpSupport {
    private HttpSupport() {}
    public static RestClient client() {
        var factory=new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection,String method) throws IOException {
                super.prepareConnection(connection,method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5000);factory.setReadTimeout(15000);
        return RestClient.builder().requestFactory(factory).build();
    }
    public static <T> T retry(Supplier<T> operation) {
        for(int attempt=0;;attempt++) {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("任务已取消");
            try { return operation.get(); }
            catch (RestClientException error) {
                boolean temporary=error instanceof ResourceAccessException;
                if(error instanceof RestClientResponseException response) temporary=java.util.Set.of(429,502,503,504).contains(response.getStatusCode().value());
                if(!temporary || attempt>=2) throw error;
                try { Thread.sleep(200L << attempt); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("任务已取消"); }
            }
        }
    }
}
