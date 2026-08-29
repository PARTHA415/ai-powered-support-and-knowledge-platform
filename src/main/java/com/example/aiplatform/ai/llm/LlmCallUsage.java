package com.example.aiplatform.ai.llm;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Method;

/**
 * What one LLM call actually consumed, in a shape this application owns.
 *
 * <p>Spring AI already reports prompt/completion tokens portably. It does not
 * report how many of those prompt tokens the provider served from its own
 * prefix cache, because no portable abstraction for that exists yet - the count
 * lives in the provider's native usage object. That number matters here: it is
 * the only evidence that prompt caching is working, and it is billed at a
 * different rate (see
 * {@link com.example.aiplatform.config.ModelPricingProperties.ModelPrice}).
 *
 * <p>So {@link #from(ChatResponse, ModelTier)} reads the portable fields
 * directly and makes one best-effort, reflective attempt at the cached count.
 * Reflection is used deliberately rather than importing the OpenAI usage type:
 * an import would put a provider class in the middle of the seam whose entire
 * purpose is not having one, and would fail to compile the day the provider
 * dependency is swapped. A provider that exposes nothing of the sort reports
 * zero cached tokens, which is exactly right - it has no prefix cache to
 * report.
 *
 * @param model               the model the provider says served the call, which
 *                            is not always the one that was requested (an alias
 *                            resolves to a dated snapshot) - and the reported
 *                            one is what was billed
 * @param tier                which tier the call site asked for, so cost can be
 *                            broken down by intent and tiering can be shown to
 *                            have worked
 * @param promptTokens        total prompt tokens, cached ones included
 * @param cachedPromptTokens  the subset of {@code promptTokens} served from the
 *                            provider's prefix cache; 0 when unknown
 * @param completionTokens    tokens generated
 */
public record LlmCallUsage(
        String model,
        ModelTier tier,
        long promptTokens,
        long cachedPromptTokens,
        long completionTokens
) {

    private static final String UNKNOWN_MODEL = "unknown";

    public static LlmCallUsage none(ModelTier tier) {
        return new LlmCallUsage(UNKNOWN_MODEL, tier, 0, 0, 0);
    }

    public static LlmCallUsage from(ChatResponse response, ModelTier tier) {
        if (response == null || response.getMetadata() == null) {
            return none(tier);
        }
        String model = response.getMetadata().getModel();
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return new LlmCallUsage(model == null || model.isBlank() ? UNKNOWN_MODEL : model, tier, 0, 0, 0);
        }
        long prompt = toLong(usage.getPromptTokens());
        long completion = toLong(usage.getCompletionTokens());
        long cached = Math.min(cachedPromptTokens(usage), prompt);
        return new LlmCallUsage(model == null || model.isBlank() ? UNKNOWN_MODEL : model, tier,
                prompt, cached, completion);
    }

    /** Prompt tokens billed at the full rate - the ones the prefix cache did not cover. */
    public long uncachedPromptTokens() {
        return Math.max(0, promptTokens - cachedPromptTokens);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean isEmpty() {
        return promptTokens == 0 && completionTokens == 0;
    }

    /**
     * Walks {@code getNativeUsage().getPromptTokensDetails().getCachedTokens()}
     * (and the record-style accessors some providers generate instead) without
     * naming any provider type. Any failure at any step means "this provider
     * does not report it", which is a zero, not an error - a metric that cannot
     * be collected must never be allowed to fail the call it was measuring.
     */
    private static long cachedPromptTokens(Usage usage) {
        try {
            Object native_ = usage.getNativeUsage();
            Object details = invokeFirst(native_, "getPromptTokensDetails", "promptTokensDetails");
            Object cached = invokeFirst(details, "getCachedTokens", "cachedTokens");
            return cached instanceof Number number ? Math.max(0, number.longValue()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Object invokeFirst(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next spelling; absence is the expected case.
            }
        }
        return null;
    }

    private static long toLong(Integer value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }
}
