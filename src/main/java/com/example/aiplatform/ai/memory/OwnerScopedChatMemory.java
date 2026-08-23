package com.example.aiplatform.ai.memory;

import com.example.aiplatform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Binds every conversation to the authenticated caller who created it.
 *
 * <p>Without this, {@code conversationId} was a caller-supplied string used
 * directly as the storage key, with nothing tying it to an identity: any
 * authenticated user who named another user's conversation ID had that
 * conversation's history loaded into their prompt and returned in their
 * answer - and their own turn appended to it. That bypassed the entire
 * tool-authorization model without touching it, because
 * {@link com.example.aiplatform.ai.tools.SupportTools} is never consulted on
 * the memory path: the other customer's order numbers, payment status, and
 * profile data had already been retrieved on a previous turn and stored
 * under a key the attacker could simply guess.
 *
 * <p>The fix is deliberately placed here, as a decorator around the
 * {@link ChatMemory} bean itself, rather than in {@code AgentServiceImpl} or
 * in {@link RedisChatMemoryRepository}. Putting it in the service would mean
 * every future caller of ChatMemory has to remember to scope its key -
 * exactly the kind of "enforced by convention" rule that eventually gets
 * missed. Putting it in the repository would couple a persistence class to
 * the security context and break its direct unit tests. As a decorator it is
 * the same "enforced once, inherited everywhere" property this codebase
 * already relies on for LLM guardrails ({@code SpringAiLlmClientService}) and
 * tool authorization ({@link CurrentUser}): there is one place a conversation
 * key can be produced, and it always includes the caller.
 *
 * <p>Identity comes from {@link CurrentUser} - the authenticated principal,
 * never the request body and never anything the model produced. There is no
 * unauthenticated path: {@code CurrentUser.get()} throws if the security
 * context holds no principal, so this fails closed rather than silently
 * falling back to a shared namespace.
 */
public class OwnerScopedChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(OwnerScopedChatMemory.class);

    private final ChatMemory delegate;

    public OwnerScopedChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(scopedKey(conversationId), messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(scopedKey(conversationId));
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(scopedKey(conversationId));
    }

    /**
     * Produces {@code <encoded-username>:<conversationId>}.
     *
     * <p>The username is percent-encoded rather than concatenated raw, and
     * that is a correctness requirement rather than tidiness. With raw
     * concatenation and a {@code ':'} separator, user {@code "a"} asking for
     * conversation {@code "b:x"} and user {@code "a:b"} asking for
     * conversation {@code "x"} both produce the key {@code "a:b:x"} - a
     * crafted conversationId could climb into another user's namespace and
     * reopen the exact hole this class exists to close. Percent-encoding
     * cannot itself emit a {@code ':'}, so the first colon in the key is
     * always the separator and the mapping is unambiguous.
     */
    private static String scopedKey(String conversationId) {
        String username = CurrentUser.get().getUsername();
        String key = URLEncoder.encode(username, StandardCharsets.UTF_8) + ":" + conversationId;
        log.trace("Scoped conversation key resolved for the authenticated caller");
        return key;
    }
}
