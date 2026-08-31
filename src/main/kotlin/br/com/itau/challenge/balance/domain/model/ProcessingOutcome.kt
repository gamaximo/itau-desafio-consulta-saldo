package br.com.itau.challenge.balance.domain.model

/**
 * What the pipeline did with a valid event. Every value here is a **success** — none of them
 * is an error worth retrying — but they are counted separately so that operators can tell a
 * healthy stream from a broken one.
 *
 * A rising [STALE_DISCARDED] rate, for instance, is normal under partition rebalancing but
 * suspicious if it persists: it would suggest a producer replaying old offsets.
 */
enum class ProcessingOutcome {
    /** The snapshot was newer than the stored one and became the current balance. */
    APPLIED,

    /**
     * The stored balance was already at or ahead of this event's version, so the write was
     * rejected by the condition. This covers both out-of-order delivery and duplicates.
     */
    STALE_DISCARDED,

    /**
     * The transaction was declined and the service is configured not to project declined
     * transactions. See `balance.apply-declined-transactions`.
     */
    DECLINED_SKIPPED,
}
