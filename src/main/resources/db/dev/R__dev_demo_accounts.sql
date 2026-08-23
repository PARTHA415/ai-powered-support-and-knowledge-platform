-- Demo accounts for LOCAL DEVELOPMENT AND TESTS ONLY.
--
-- These four accounts all share the password "password", and the BCrypt hash
-- below is committed to this repository - which means the credential is
-- public and must never exist in an environment that holds real data. One of
-- them (dave) is an ADMIN, and ADMIN reaches POST /api/embeddings and
-- POST /api/eval/run, both of which spend real money against the LLM
-- provider.
--
-- This file is therefore NOT loaded by default. It is a Flyway REPEATABLE
-- migration that lives in db/dev, a location added to spring.flyway.locations
-- only under the "dev" profile. Repeatable migrations run after every
-- versioned one, and the ON CONFLICT clause makes re-running a no-op.
--
-- A deployment that does not set SPRING_PROFILES_ACTIVE=dev gets an empty app_user table and no way to log in until real accounts are
-- provisioned - which is the correct default: secure unless someone opts in,
-- rather than safe only if someone remembers to opt out.
--
-- alice/bob match the CUST-1001/CUST-1002 fixture customers in
-- BusinessDataStore, so tool-level ownership checks have real matching data
-- to authorize against. carol is a SUPPORT_AGENT and dave is an ADMIN; staff
-- accounts leave customer_id NULL because ownership is never checked for them.
INSERT INTO app_user (username, password_hash, customer_id, role) VALUES
    ('alice', '$2a$10$D0N.Q5yAWsveP9/MVqc2O.p7t99qpulLWI7VFgT1/HtACJR6wJcWC', 'CUST-1001', 'USER'),
    ('bob',   '$2a$10$D0N.Q5yAWsveP9/MVqc2O.p7t99qpulLWI7VFgT1/HtACJR6wJcWC', 'CUST-1002', 'USER'),
    ('carol', '$2a$10$D0N.Q5yAWsveP9/MVqc2O.p7t99qpulLWI7VFgT1/HtACJR6wJcWC', NULL, 'SUPPORT_AGENT'),
    ('dave',  '$2a$10$D0N.Q5yAWsveP9/MVqc2O.p7t99qpulLWI7VFgT1/HtACJR6wJcWC', NULL, 'ADMIN')
ON CONFLICT (username) DO NOTHING;
