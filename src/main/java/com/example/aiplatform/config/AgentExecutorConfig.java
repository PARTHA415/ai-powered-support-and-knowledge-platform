package com.example.aiplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pool the agent workflow runs its independent capability steps on, and
 * the mechanism that makes running them off the request thread safe.
 *
 * <h2>Why a pool at all</h2>
 *
 * The agent's knowledge-base search and its business-tool round do not depend
 * on each other - one queries pgvector, the other calls the LLM with tools -
 * and running them in sequence adds their latencies for no reason. Running them
 * concurrently makes the agent's capability phase as slow as its slowest step
 * rather than as slow as their sum, which on a two-step plan is close to
 * halving it.
 *
 * <h2>Why the security context is delegated</h2>
 *
 * {@link DelegatingSecurityContextExecutorService} copies the submitting
 * thread's {@code SecurityContext} onto the worker. Without it, every
 * authorization check inside a tool would throw "no authenticated caller in the
 * security context" the moment the tool round moved off the request thread -
 * the tools read the real principal from Spring Security rather than from
 * anything the model supplies, which is exactly the property that makes them
 * safe and exactly what breaks under a naive thread hand-off. Using Spring
 * Security's own decorator rather than copying the context by hand means this
 * keeps working if the context strategy changes.
 *
 * <p>The application's own per-request state travels the same way, through
 * {@link com.example.aiplatform.observability.RequestContextHolder#propagate} at
 * the submission site. Two mechanisms rather than one because they are owned by
 * two different parties, and wrapping Spring Security's context by hand to save
 * a decorator would be the wrong kind of clever.
 *
 * <h2>Platform threads, not virtual ones</h2>
 *
 * This module targets Java 17, where virtual threads do not exist. These tasks
 * are almost entirely blocked on network I/O, which is precisely the workload
 * virtual threads exist for, so on Java 21+ this bean becomes
 * {@code Executors.newVirtualThreadPerTaskExecutor()} and the bounded pool and
 * its rejection policy stop being necessary. Until then the pool is small and
 * bounded on purpose: an unbounded pool against a rate-limited, billed provider
 * converts a traffic spike into a spend spike.
 */
@Configuration
public class AgentExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentStepExecutor(AgentProperties agentProperties) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "agent-step-" + counter.getAndIncrement());
                // Daemon so a hung provider call cannot hold shutdown open past
                // the graceful-shutdown window; the in-flight request has its
                // own deadline and will already have given up on it.
                thread.setDaemon(true);
                return thread;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, Math.max(2, agentProperties.maxConcurrentSteps()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                threadFactory,
                // Abort rather than CallerRuns. CallerRuns would execute the
                // task on the request thread, which sounds like graceful
                // degradation and is not: the agent submits its steps and then
                // waits on them with a deadline, and a task running inline
                // cannot be abandoned when that deadline passes. Aborting lets
                // the agent notice and fall back to running the steps in
                // sequence, which is slower and still bounded.
                new ThreadPoolExecutor.AbortPolicy());

        return new DelegatingSecurityContextExecutorService(executor);
    }
}
