package com.example.aiplatform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 16: a dedicated logger name ({@code AUDIT}), separate from the
 * per-class loggers everywhere else in this codebase, specifically so an
 * operator can route it independently - a different log level, a different
 * file appender, a different shipping destination (a SIEM, a write-once
 * store) - via ordinary logback configuration, without touching a single
 * line of Java. Not wired to a separate appender in this phase (see the
 * Phase 16 docs' production considerations for why that's a deliberately
 * deferred, environment-specific decision), but the seam exists now, which
 * is the part that has to be decided in code.
 *
 * Every authorization DECISION gets a line - both ALLOW and DENY - not just
 * denials: "who was allowed to see what" is exactly as much a security-
 * relevant fact as "who was blocked," and an audit trail with only denials
 * in it can't answer "did anyone access this customer's data last month,"
 * only "did anyone get refused."  The caller's identity and the resource
 * come from {@link CurrentUser}/its caller, never from anything the LLM
 * supplied - the same principle {@link com.example.aiplatform.ai.tools.SupportTools}
 * already applies to the authorization decision itself, extended to its
 * record.
 */
public final class AuditLogger {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    private AuditLogger() {
    }

    public static void logToolAccess(String resourceDescription, boolean allowed, String callerDescription) {
        if (allowed) {
            AUDIT_LOG.info("ALLOW caller={} resource=\"{}\"", callerDescription, resourceDescription);
        } else {
            AUDIT_LOG.warn("DENY caller={} resource=\"{}\"", callerDescription, resourceDescription);
        }
    }
}
