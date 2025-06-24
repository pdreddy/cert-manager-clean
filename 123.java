import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.SslOptions;
import io.lettuce.core.ClientOptions;

public class RedisLettuceExample {
    // Configuration constants
    private static final String MASTER_ENDPOINT = "vvvvv1.cache.amazonaws.com";
    private static final String REPLICA_ENDPOINT = "rzzzzzl.use1.cache.amazonaws.com";
    private static final String CONFIG_ENDPOINT = "<configuration-endpoint>"; // Replace with actual config endpoint if cluster mode enabled
    private static final int PORT = 6379;
    private static final String PASSWORD = "<your-password>"; // Replace with auth token if enabled, or null if not
    private static final boolean USE_TLS = false; // Set to true if TLS is enabled

    // Cluster Mode Disabled: Connect to master or replica using RedisClient
    public static void connectClusterModeDisabled(String endpoint, boolean isReadOnly) {
        try {
            // Build Redis URI
            String protocol = USE_TLS ? "rediss://" : "redis://";
            String auth = (PASSWORD != null && !PASSWORD.isEmpty()) ? ":" + PASSWORD + "@" : "";
            String redisUri = protocol + auth + endpoint + ":" + PORT;

            // Create RedisClient
            RedisClient redisClient = RedisClient.create(redisUri);

            // Configure TLS if enabled (optional: customize truststore or disable hostname verification for testing)
            if (USE_TLS) {
                SslOptions sslOptions = SslOptions.builder().jdkSslProvider().build();
                ClientOptions clientOptions = ClientOptions.builder().sslOptions(sslOptions).build();
                redisClient.setOptions(clientOptions);
            }

            // Connect
            StatefulRedisConnection<String, String> connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();

            // Demo: Set and get a key
            if (!isReadOnly) {
                syncCommands.set("key", "value");
                System.out.println("Set key=value on " + endpoint);
            }
            String value = syncCommands.get("key");
            System.out.println("Got value from " + endpoint + ": " + value);

            // Cleanup
            connection.close();
            redisClient.shutdown();
        } catch (Exception e) {
            System.err.println("Error in cluster mode disabled: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Cluster Mode Enabled: Connect to configuration endpoint using RedisClusterClient
    public static void connectClusterModeEnabled() {
        try {
            // Build Redis URI
            String protocol = USE_TLS ? "rediss://" : "redis://";
            String auth = (PASSWORD != null && !PASSWORD.isEmpty()) ? ":" + PASSWORD + "@" : "";
            String redisUri = protocol + auth + CONFIG_ENDPOINT + ":" + PORT;

            // Create RedisClusterClient
            RedisClusterClient clusterClient = RedisClusterClient.create(redisUri);

            // Configure TLS if enabled
            if (USE_TLS) {
                SslOptions sslOptions = SslOptions.builder().jdkSslProvider().build();
                ClientOptions clientOptions = ClientOptions.builder().sslOptions(sslOptions).build();
                clusterClient.setOptions(clientOptions);
            }

            // Connect
            StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
            RedisAdvancedClusterCommands<String, String> syncCommands = connection.sync();

            // Demo: Set and get a key
            syncCommands.set("key", "value");
            System.out.println("Set key=value on cluster " + CONFIG_ENDPOINT);
            String value = syncCommands.get("key");
            System.out.println("Got value from cluster " + CONFIG_ENDPOINT + ": " + value);

            // Cleanup
            connection.close();
            clusterClient.shutdown();
        } catch (Exception e) {
            System.err.println("Error in cluster mode enabled: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Demo: Cluster Mode Disabled - Connect to master for writes
        System.out.println("=== Cluster Mode Disabled (Master) ===");
        connectClusterModeDisabled(MASTER_ENDPOINT, false);

        // Demo: Cluster Mode Disabled - Connect to replica for reads
        System.out.println("=== Cluster Mode Disabled (Replica) ===");
        connectClusterModeDisabled(REPLICA_ENDPOINT, true);

        // Demo: Cluster Mode Enabled (uncomment if applicable)
        // Note: Replace CONFIG_ENDPOINT with actual configuration endpoint
        // System.out.println("=== Cluster Mode Enabled ===");
        // connectClusterModeEnabled();
    }
}
