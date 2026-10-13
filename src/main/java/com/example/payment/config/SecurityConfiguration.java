package com.example.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Authentication: an OAuth2 resource server. Every request needs a valid JWT
 * bearer token, except health (and its probes), metrics and the API documentation. The token's
 * {@code sub} is the customer ID. The service only consumes tokens: it has no
 * users, no login and no roles.
 *
 * Who may touch which payment is not decided here but in the application
 * services, which never see a token, only the customer ID.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
								"/swagger-ui.html", "/swagger-ui/**").permitAll()
						.anyRequest().authenticated())
				// No answer is a 401 with WWW-Authenticate: Bearer.
				.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
				// The token comes with every request, so there is no session and no cookie for CSRF to abuse.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.build();
	}

	// Locally, tokens are signed with a shared key from application-local.properties, so no identity provider has
	// to run. Anywhere else there is no such bean: Spring Boot builds the decoder from the identity provider's
	// public keys (spring.security.oauth2.resourceserver.jwt.issuer-uri), and without one the service doesn't start.
	@Bean
	@Profile("local")
	JwtDecoder localJwtDecoder(@Value("${payment.security.local-jwt-secret}") String secret) {
		return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
				.build();
	}
}
