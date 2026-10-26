@Configuration
@Profile("local")
public class LocalInsecureMongoConfig {

    @Value("${spring.data.mongodb.uri}")   // use whatever key your yml actually uses
    private String mongoUri;               // (spring.mongodb.uri on Boot 4)

    @Bean
    public MongoClientSettings mongoClientSettings() throws SSLException {
        SslContext trustAll = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoUri)) // hosts, creds, replicaSet, etc.
                .applyToSslSettings(ssl -> ssl
                        .enabled(true)
                        .invalidHostNameAllowed(true))
                .transportSettings(TransportSettings.nettyBuilder()
                        .sslContext(trustAll)
                        .build())
                .build();
    }
}