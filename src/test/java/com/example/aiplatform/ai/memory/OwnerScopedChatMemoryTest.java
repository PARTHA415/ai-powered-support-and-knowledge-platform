package com.example.aiplatform.ai.memory;

import com.example.aiplatform.model.AppUser;
import com.example.aiplatform.model.Role;
import com.example.aiplatform.security.AppUserPrincipal;
import com.example.aiplatform.security.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The regression test for the cross-user conversation leak.
 *
 * <p>Before ownership scoping, {@code conversationId} was a caller-supplied
 * string used directly as the storage key. Any authenticated user who named
 * another user's conversation ID had that conversation's history loaded into
 * their prompt and returned in their answer - a data leak that never touched
 * {@code SupportTools} and so was invisible to every tool-authorization test
 * in this codebase.
 *
 * <p>Deliberately unit-scoped with an in-memory delegate rather than added to
 * the Redis integration test: the property under test is about key derivation,
 * not storage, and this way it runs on a machine with no Docker daemon - which
 * is exactly where a regression in a security control must not go unnoticed.
 */
class OwnerScopedChatMemoryTest {

    private final RecordingChatMemory delegate = new RecordingChatMemory();
    private final ChatMemory memory = new OwnerScopedChatMemory(delegate);

    @AfterEach
    void clearSecurityContext() {
        TestPrincipals.clear();
    }

    @Test
    void oneUserCannotReadAnotherUsersConversationWithTheSameId() {
        authenticateAs("alice");
        memory.add("shared-id", List.of(new UserMessage("My order is ORD-1001")));

        authenticateAs("mallory");

        assertThat(memory.get("shared-id"))
                .as("naming another user's conversation ID must not return their history")
                .isEmpty();
    }

    @Test
    void oneUserCannotAppendToAnotherUsersConversation() {
        authenticateAs("alice");
        memory.add("shared-id", List.of(new UserMessage("turn one")));

        authenticateAs("mallory");
        memory.add("shared-id", List.of(new AssistantMessage("injected by another user")));

        authenticateAs("alice");
        assertThat(memory.get("shared-id"))
                .extracting(Message::getText)
                .as("another user's write must not land in this user's conversation")
                .containsExactly("turn one");
    }

    @Test
    void theSameUserStillSeesTheirOwnConversationAcrossCalls() {
        authenticateAs("alice");
        memory.add("conv-1", List.of(new UserMessage("My order is ORD-1001")));
        memory.add("conv-1", List.of(new AssistantMessage("Found it.")));

        assertThat(memory.get("conv-1"))
                .extracting(Message::getText)
                .containsExactly("My order is ORD-1001", "Found it.");
    }

    @Test
    void oneUsersConversationsStayDistinctFromEachOther() {
        authenticateAs("alice");
        memory.add("conv-a", List.of(new UserMessage("about order A")));
        memory.add("conv-b", List.of(new UserMessage("about order B")));

        assertThat(memory.get("conv-a")).extracting(Message::getText).containsExactly("about order A");
        assertThat(memory.get("conv-b")).extracting(Message::getText).containsExactly("about order B");
    }

    /**
     * Guards the percent-encoding in {@code scopedKey}. With naive
     * concatenation, user {@code "a"} asking for conversation {@code "b:x"}
     * and user {@code "a:b"} asking for conversation {@code "x"} would both
     * resolve to the key {@code "a:b:x"} - a crafted conversationId climbing
     * into another user's namespace, which is the very hole this class closes.
     */
    @Test
    void aCraftedConversationIdCannotCollideWithAnotherUsersNamespace() {
        authenticateAs("a:b");
        memory.add("x", List.of(new UserMessage("belongs to the user named a:b")));

        authenticateAs("a");

        assertThat(memory.get("b:x"))
                .as("a colon in the username must not let another caller reach the same key")
                .isEmpty();
    }

    @Test
    void anUnauthenticatedCallerIsRejectedRatherThanSharingANamespace() {
        TestPrincipals.clear();

        assertThatThrownBy(() -> memory.get("conv-1"))
                .as("no principal must fail closed, never fall back to an unscoped key")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearOnlyAffectsTheCallersOwnConversation() {
        authenticateAs("alice");
        memory.add("shared-id", List.of(new UserMessage("alice's turn")));

        authenticateAs("mallory");
        memory.clear("shared-id");

        authenticateAs("alice");
        assertThat(memory.get("shared-id"))
                .as("another user must not be able to delete this user's history")
                .extracting(Message::getText)
                .containsExactly("alice's turn");
    }

    private static void authenticateAs(String username) {
        TestPrincipals.authenticateAs(
                new AppUserPrincipal(new AppUser(username, "unused", "CUST-1001", Role.USER)));
    }

    /**
     * Stands in for the windowed, Redis-backed memory. Records whatever key it
     * is handed, so the test asserts on observable behaviour (what a caller can
     * read back) rather than on the key format itself - the format is an
     * implementation detail, the isolation is the contract.
     */
    private static final class RecordingChatMemory implements ChatMemory {

        private final Map<String, List<Message>> byKey = new HashMap<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            byKey.computeIfAbsent(conversationId, key -> new ArrayList<>()).addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(byKey.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void clear(String conversationId) {
            byKey.remove(conversationId);
        }
    }
}
