import com.mongodb.MongoClientSettings;
import com.mongodb.connection.TransportSettings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.net.ssl.SSLException;

@Configuration
@Profile("local") // only active with -Dspring.profiles.active=local
public class LocalInsecureMongoConfig {

    @Bean
    public MongoClientSettings mongoClientSettings() throws SSLException {
        SslContext trustAll = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE) // accepts any server cert
                .build();

        return MongoClientSettings.builder()
                .applyToSslSettings(ssl -> ssl
                        .enabled(true)                  // keep TLS on, just skip validation
                        .invalidHostNameAllowed(true))  // skip hostname check
                .transportSettings(TransportSettings.nettyBuilder()
                        .sslContext(trustAll)
                        .build())
                .build();
    }
}