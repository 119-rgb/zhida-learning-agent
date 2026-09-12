package com.zhida.agent.tool;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.*;
import org.springframework.http.HttpStatus;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class HttpSupportTest {
    @Test void retriesTemporaryErrorsButNeverAuthenticationFailures() {
        var count=new AtomicInteger();
        String result=HttpSupport.retry(()->{if(count.incrementAndGet()<3)throw new ResourceAccessException("temporary");return "ok";});
        assertThat(result).isEqualTo("ok");assertThat(count.get()).isEqualTo(3);
        count.set(0);
        assertThatThrownBy(()->HttpSupport.retry(()->{count.incrementAndGet();throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED);}))
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(count.get()).isEqualTo(1);
    }
}
