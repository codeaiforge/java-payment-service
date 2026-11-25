package com.example.payment.infrastructure.adapter.primary.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A model that answers with the given texts, in order, and keeps what it was asked. No real model runs in a test. */
public class ScriptedChatModel implements ChatModel {

	private final Deque<String> answers = new ArrayDeque<>();
	private final List<Prompt> prompts = new ArrayList<>();

	public ScriptedChatModel answering(String... answers) {
		this.answers.addAll(List.of(answers));
		return this;
	}

	@Override
	public synchronized ChatResponse call(Prompt prompt) {
		prompts.add(prompt);
		if (answers.isEmpty()) {
			throw new AssertionError("The model was asked more often than the test scripted");
		}
		return new ChatResponse(List.of(new Generation(new AssistantMessage(answers.removeFirst()))));
	}

	public synchronized List<Prompt> prompts() {
		return List.copyOf(prompts);
	}

	public synchronized void reset() {
		answers.clear();
		prompts.clear();
	}
}
