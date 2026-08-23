package com.example.aiplatform.ai.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Replaces Spring AI's {@code MessageWindowChatMemory} for this application.
 *
 * <p>That class is perfectly correct in isolation, but its {@code add} is
 * read-modify-write by construction: fetch the whole conversation, combine,
 * truncate in Java, write it all back. Two concurrent turns in the same
 * conversation both read the same starting state and the later write silently
 * discards the earlier turn - a lost message with no error anywhere.
 *
 * <p>The sliding-window bound still exists and is still enforced on every
 * append; it has simply moved to where the append happens, inside
 * {@link RedisChatMemoryRepository#appendAll}'s Lua script, so trimming and
 * appending are one atomic operation rather than two racy ones. The bound
 * remains configured by app.memory.max-messages.
 *
 * <p>This sits below {@link OwnerScopedChatMemory}, so every conversation ID
 * reaching it has already been scoped to the authenticated caller.
 */
public class RedisWindowChatMemory implements ChatMemory {

    private final RedisChatMemoryRepository repository;
    private final int maxMessages;

    public RedisWindowChatMemory(RedisChatMemoryRepository repository, int maxMessages) {
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        repository.appendAll(conversationId, messages, maxMessages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return repository.findByConversationId(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }
}
