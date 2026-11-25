package com.example.payment.config;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI's Anthropic and OpenAI clients run on OkHttp, which the pom manages to OkHttp 5 so the OpenTelemetry
 * exporter and they share one copy (see the pom). This sends one real request through each, to a stub: a client
 * that couldn't work on OkHttp 5, or read its answer with the Jackson 2 its SDK brings, fails here and not in front
 * of an operator. No real model is called.
 */
class AgentDependenciesTest {

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class,
					ToolCallingAutoConfiguration.class, AnthropicChatAutoConfiguration.class,
					OpenAiChatAutoConfiguration.class));

	@Test
	void anthropicAnswersOverOkHttp5() {
		provider.stubFor(post(urlPathMatching(".*/messages")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"id": "msg_1", "type": "message", "role": "assistant", "model": "stub",
						 "content": [{"type": "text", "text": "pong"}], "stop_reason": "end_turn",
						 "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1}}""")));

		runner.withPropertyValues("spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=test",
				"spring.ai.anthropic.base-url=" + provider.baseUrl())
				.run(context -> assertThat(context.getBean(ChatModel.class).call("ping")).isEqualTo("pong"));
	}

	@Test
	void openAiAnswersOverOkHttp5() {
		provider.stubFor(post(urlPathMatching(".*/chat/completions")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "stub",
						 "choices": [{"index": 0, "finish_reason": "stop",
						              "message": {"role": "assistant", "content": "pong", "refusal": null}}],
						 "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}""")));

		runner.withPropertyValues("spring.ai.model.chat=openai", "spring.ai.openai.api-key=test",
				"spring.ai.openai.base-url=" + provider.baseUrl())
				.run(context -> assertThat(context.getBean(ChatModel.class).call("ping")).isEqualTo("pong"));
	}
}
