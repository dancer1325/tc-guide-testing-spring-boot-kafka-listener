package com.testcontainers.demo;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@TestPropertySource(
        properties = {
                "spring.kafka.consumer.auto-offset-reset=earliest",
                // If the message is published, although the listener is not ready -> listener consumes it
                "spring.datasource.url=jdbc:tc:mysql:8.0.32:///db",
                // Testcontainers special JDBC URL
        }
)
@Testcontainers
class ProductPriceChangedEventHandlerTest {

    @Container
    static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.3.3")
    );

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProductRepository productRepository;

    /**
     * Create a Product record in MySQL previous to run any test
     */
    @BeforeEach
    void setUp() {
        Product product = new Product(null, "P100", "Product One", BigDecimal.TEN);
        productRepository.save(product);
    }

    /**
     * Publish a Product record in the "product-price-changes" kafka topic, and
     * checking that it's persisted in MySQL
     */
    @Test
    void shouldHandleProductPriceChangedEvent() {
        ProductPriceChangedEvent event = new ProductPriceChangedEvent(
                "P100",
                new BigDecimal("14.50")
        );

        kafkaTemplate.send("product-price-changes", event.productCode(), event);

        // Await during [3, 10] seconds to check if it's persisted in the database
        await()
                .pollInterval(Duration.ofSeconds(3))
                .atMost(10, SECONDS)
                .untilAsserted(() -> {
                    Optional<Product> optionalProduct = productRepository.findByCode("P100");
                    assertThat(optionalProduct).isPresent();
                    assertThat(optionalProduct.get().getCode()).isEqualTo("P100");
                    assertThat(optionalProduct.get().getPrice()).isEqualTo(new BigDecimal("14.50"));
                });
    }
}
