package com.example.aiplatform.ai.rag;

import com.example.aiplatform.model.Role;
import com.example.aiplatform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Decides which knowledge-base documents the current caller may retrieve.
 *
 * <p>Until now the knowledge base had no per-document access control at all:
 * ingestion was staff-only, but retrieval was open to every authenticated
 * caller including customers. That was fine while every document was
 * customer-safe, and became a leak the moment one internal runbook was
 * ingested - with no mechanism available to prevent it, because there were no
 * metadata columns to express visibility on.
 *
 * <p>Now there are. A document tagged {@code audience: internal} is excluded
 * for customer callers; staff see everything.
 *
 * <p><b>Two honest limitations, stated rather than papered over.</b> First,
 * this is exclusion-based, so it protects only documents that were actually
 * tagged - an untagged internal runbook is still visible to customers. The
 * alternative (require every document to carry {@code audience: customer} to be
 * visible at all) fails closed and is stricter, but would make an existing
 * untagged corpus invisible to customers overnight; that migration is a
 * deliberate decision for whoever owns the corpus, not something to impose
 * silently here. Second, real per-document ACLs - groups, ownership, sharing -
 * are a larger design than one metadata key.
 *
 * <p>What this does guarantee is that the check cannot be forgotten or
 * bypassed: it is applied inside {@link PgVectorSemanticSearchService}, the
 * single point every retrieval path already goes through, so {@code /api/qa},
 * {@code /api/documents/search}, the agent's knowledge-base step, and the
 * MCP-exposed {@code searchKnowledgeBase} tool all inherit it - and the
 * exclusion travels in a field of
 * {@link com.example.aiplatform.model.RetrievalFilter} that no request body can
 * write to.
 */
@Component
public class KnowledgeBaseAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseAccessPolicy.class);

    public static final String AUDIENCE_KEY = "audience";
    public static final String AUDIENCE_INTERNAL = "internal";

    private static final Map<String, String> EXCLUDE_INTERNAL = Map.of(AUDIENCE_KEY, AUDIENCE_INTERNAL);

    /**
     * Fails closed. An absent principal yields the RESTRICTIVE exclusion, not
     * an empty one: if we cannot establish who is asking, the answer is the
     * customer-visible subset, never everything. Retrieval has non-HTTP entry
     * points (tools, tests) where no security context may be present, and the
     * safe default there must not be "show internal documents".
     */
    public Map<String, String> exclusionsForCurrentCaller() {
        Role role;
        try {
            role = CurrentUser.role();
        } catch (IllegalStateException e) {
            log.debug("No authenticated caller in context for a knowledge-base search; "
                    + "applying the most restrictive audience filter");
            return EXCLUDE_INTERNAL;
        }
        if (role == Role.SUPPORT_AGENT || role == Role.ADMIN) {
            return Map.of();
        }
        return EXCLUDE_INTERNAL;
    }
}
