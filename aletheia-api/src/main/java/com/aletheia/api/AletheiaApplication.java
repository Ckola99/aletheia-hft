package com.aletheia.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the Aletheia trading engine.
 *
 * WHAT THESE ANNOTATIONS DO:
 *
 * @SpringBootApplication — combines three things:
 *                        1. @ComponentScan — finds all classes annotated
 *                        with @Service,
 *                        @Repository, @Controller etc. and registers them as
 *                        beans
 *                        2. @EnableAutoConfiguration — auto-configures Spring
 *                        based on
 *                        what libraries are on the classpath (detects Postgres
 *                        driver
 *                        → configures a DataSource, detects Spring Web →
 *                        configures
 *                        an embedded Tomcat server, etc.)
 *                        3. @Configuration — this class itself can define @Bean
 *                        methods
 *
 *                        scanBasePackages = "com.aletheia" — tells Spring to
 *                        scan ALL our
 *                        modules, not just the api package. Without this,
 *                        Spring would only
 *                        find beans inside com.aletheia.api and ignore
 *                        everything in
 *                        com.aletheia.strategy, com.aletheia.calendar, etc.
 *
 * @EnableScheduling — activates the @Scheduled annotation.
 *                   The Forex Factory calendar scraper uses @Scheduled to run
 *                   its scrape job twice daily. Without this
 *                   annotation, @Scheduled
 *                   methods are silently ignored.
 */

@SpringBootApplication(scanBasePackages = "com.aletheia")
@EnableScheduling
public class AletheiaApplication {

	public static void main(String[] args) {
		SpringApplication.run(AletheiaApplication.class, args);
	}
}
