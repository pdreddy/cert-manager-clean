import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

public class RedisTemplateMasterReplica {
    public static void main(String[] args) {
        // Replace with your ElastiCache master and replica endpoints and password
        String masterHost = "your-elasticache-primary-endpoint.cache.amazonaws.com"; // e.g., myredis-primary.abcdef.0001.use1.cache.amazonaws.com
        String replicaHost = "your-elasticache-replica-endpoint.cache.amazonaws.com"; // e.g., myredis-replica.abcdef.0001.use1.cache.amazonaws.com
        String redisPassword = "your_redis_password";

        // Configure Redis standalone connection with master and replicas
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(masterHost);
        redisConfig.setPort(6379);
        redisConfig.setPassword(redisPassword);
        // Add replica node
        redisConfig.setReadReplicas(new RedisNode[]{
            new RedisNode(replicaHost, 6379)
        });

        // Configure Lettuce client with TLS and replica reads
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(30))
                .useSsl()
                .clientName("my-redis-client")
                .readFrom(ReadFrom.REPLICA_PREFERRED) // Prefer reads from replicas
                .build();

        // Create connection factory
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig, clientConfig);
        try {
            // Initialize connection factory
            connectionFactory.afterPropertiesSet();
            System.out.println("Connection factory initialized successfully");

            // Create StringRedisTemplate
            StringRedisTemplate redisTemplate = new StringRedisTemplate();
            redisTemplate.setConnectionFactory(connectionFactory);
            redisTemplate.afterPropertiesSet();
            System.out.println("RedisTemplate initialized successfully");

            // Test connection by pinging master
            String pingMaster = redisTemplate.execute((RedisConnection connection) ->
                connection.sync().ping());
            System.out.println("Ping master result: " + pingMaster);

            // Test connection by pinging replica
            String pingReplica = redisTemplate.execute((RedisConnection connection) -> {
                RedisCommands<String, String> commands = ((io.lettuce.core.api.StatefulRedisConnection) connection.getNativeConnection()).sync();
                commands.readOnly(); // Switch to replica
                String result = commands.ping();
                commands.readWrite(); // Switch back to master
                return result;
            });
            System.out.println("Ping replica result: " + pingReplica);

            // Save text to master
            String key = "greeting";
            String value = "hello java";
            redisTemplate.opsForValue().set(key, value); // Writes to master
            System.out.println("Saved to master: " + key + " = " + value);

            // Fetch text from replica
            String fetchedValue = redisTemplate.opsForValue().get(key); // Reads from replica (REPLICA_PREFERRED)
            System.out.println("Fetched from replica: " + key + " = " + fetchedValue);

            // Fetch server details from replica using INFO command
            String serverInfo = redisTemplate.execute((RedisConnection connection) -> {
                RedisCommands<String, String> commands = ((io.lettuce.core.api.StatefulRedisConnection) connection.getNativeConnection()).sync();
                commands.readOnly(); // Switch to replica
                String result = commands.info("server");
                commands.readWrite(); // Switch back to master
                return result;
            });
            System.out.println("\nServer Info (from replica):\n" + serverInfo);

            String memoryInfo = redisTemplate.execute((RedisConnection connection) -> {
                RedisCommands<String, String> commands = ((io.lettuce.core.api.StatefulRedisConnection) connection.getNativeConnection()).sync();
                commands.readOnly(); // Switch to replica
                String result = commands.info("memory");
                commands.readWrite(); // Switch back to master
                return result;
            });
            System.out.println("Memory Info (from replica):\n" + memoryInfo);

            // Delete text from master
            Boolean deleted = redisTemplate.delete(key); // Deletes from master
            System.out.println("Deleted key '" + key + "' from master: " + (deleted ? "Success" : "Key not found"));

            // Verify deletion from replica
            String afterDelete = redisTemplate.opsForValue().get(key);
            System.out.println("After deletion, fetched from replica: " + key + " = " + (afterDelete != null ? afterDelete : "null"));

        } catch (RedisException e) {
            System.err.println("Redis connection error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up
            connectionFactory.destroy();
            System.out.println("Connection factory destroyed");
        }
    }
}
