package com.example.aiplatform.service;

import com.example.aiplatform.model.AskResponse;

public interface QuestionAnsweringService {

    /**
     * The normal serving path. May return a semantically-similar answer from
     * the cache without retrieving or generating anything.
     */
    AskResponse answer(String question);

    /**
     * The same pipeline with the semantic answer cache bypassed in both
     * directions - not read from, not written to.
     *
     * <p>This exists for the evaluation harness, and the reason is worth
     * stating plainly: an eval that can be served from the cache is not
     * measuring the system. The harness's entire premise is "run it, change a
     * prompt or a model, run it again, diff the two reports" - and a cached
     * second run returns the FIRST run's answers, so the diff is empty and the
     * change looks like it did nothing. That failure is silent and it is
     * convincing, because a green report is exactly what you were hoping to
     * see.
     *
     * <p>It was not hypothetical. A knowledge-base document was corrected and
     * the eval kept reporting the pre-fix answer byte-for-byte, through an app
     * restart, while a direct call to the same endpoint returned the corrected
     * answer immediately.
     *
     * <p>Not writing is as important as not reading: an eval run that populated
     * the cache would seed real users' answers with eval traffic, and would
     * guarantee the next eval run was a cache hit.
     */
    AskResponse answerWithoutCache(String question);
}
