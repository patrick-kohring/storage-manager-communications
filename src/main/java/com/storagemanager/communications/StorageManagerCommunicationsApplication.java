package com.storagemanager.communications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The messaging starter's auto-configuration declares its own
 * {@code @EntityScan}/{@code @EnableJpaRepositories} (for the outbox/inbox JPA types under
 * {@code com.storagemanager.messaging}). Those annotations suppress Spring Boot's default
 * auto-registration that would otherwise scan this application's own package. We therefore
 * declare explicit {@code @EntityScan}/{@code @EnableJpaRepositories} for this application's
 * package so the host entities and repositories (e.g. {@code CommunicationTemplate} /
 * {@code CommunicationTemplateRepository}) continue to be registered. The starter's own
 * annotations still register the outbox/inbox types under {@code com.storagemanager.messaging},
 * so the two scans are complementary and neither double-registers the other's beans.
 */
@SpringBootApplication
@EntityScan(basePackages = "com.storagemanager.communications")
@EnableJpaRepositories(basePackages = "com.storagemanager.communications")
public class StorageManagerCommunicationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageManagerCommunicationsApplication.class, args);
    }
}
