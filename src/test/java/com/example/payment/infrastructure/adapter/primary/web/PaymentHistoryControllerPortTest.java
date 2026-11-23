package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.usecase.query.GetPaymentHistoryQuery;
import com.example.payment.application.usecase.query.StatusTransition;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As PaymentControllerPortTest: the controller only knows the primary port, so a lambda stands in for the service. */
class PaymentHistoryControllerPortTest {

	private static final Principal CALLER = () -> "CUST-1001";

	@Test
	void passesThePathIdAndCallerAsAQueryAndMapsTheTrailInOrder() throws Exception {
		MockMvc mockMvc = mockMvc(new PaymentHistoryController(query -> {
			assertThat(query).isEqualTo(new GetPaymentHistoryQuery("pay-1", "CUST-1001"));
			return List.of(
					new StatusTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, Instant.parse("2026-11-23T10:00:00Z")),
					new StatusTransition(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING, Instant.parse("2026-11-23T10:00:01Z")));
		}));

		mockMvc.perform(get("/payments/pay-1/history").principal(CALLER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].from").value("CREATED"))
				.andExpect(jsonPath("$[0].to").value("AUTHORIZED"))
				.andExpect(jsonPath("$[1].to").value("PROCESSING"))
				.andExpect(jsonPath("$[1].at").exists());
	}

	@Test
	void mapsAPaymentNotFoundTo404() throws Exception {
		PaymentId unknown = new PaymentId(UUID.randomUUID());

		mockMvc(new PaymentHistoryController(query -> { throw new PaymentNotFoundException(unknown); }))
				.perform(get("/payments/{id}/history", unknown).principal(CALLER))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Payment " + unknown + " does not exist"));
	}

	private static MockMvc mockMvc(PaymentHistoryController controller) {
		return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new PaymentExceptionHandler()).build();
	}
}
