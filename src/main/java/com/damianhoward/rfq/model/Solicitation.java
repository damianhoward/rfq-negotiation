package com.damianhoward.rfq.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The request as the makers see it — the outbound half.
 *
 * <p><b>It is not the request that came in.</b> The taker's request carries the taker's interest
 * and the taker's clock; this carries the system's, a window of minutes rather than seconds, set
 * independently of how long the taker intends to wait.
 *
 * <p>Keeping them apart is what makes the rest coherent. The taker's interest can end while this is
 * still open — a real state, not an error: the makers are told the interest that prompted them has
 * gone, their quotes are untouched, and the liquidity stays in the book for whoever asks next. One
 * object shown to both sides could not express that, and would have forced a choice between cutting
 * the solicitation short and pretending the taker was still there.
 *
 * <p>It also holds each maker's answer, because a maker declining is an answer to a solicitation
 * rather than the fate of a quote that was never made. When every maker has declined there is
 * nothing left to wait for, and saying so beats letting the clock run out on an empty room.
 */
public final class Solicitation {

  private final RequestId request;
  private final Instant expiresAt;
  private final Map<ParticipantId, Answer> answers = new LinkedHashMap<>();
  private boolean interestGone;

  /** Where a solicited maker has got to. */
  public enum Answer {
    /** Asked, and has not yet said anything. */
    PENDING,
    /** Has a quote in. */
    QUOTED,
    /** Said no — not interested in pricing this one. */
    DECLINED
  }

  public Solicitation(RequestId request, Set<ParticipantId> makers, Instant expiresAt) {
    this.request = java.util.Objects.requireNonNull(request, "request");
    this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
    java.util.Objects.requireNonNull(makers, "makers");
    if (makers.isEmpty()) {
      throw new IllegalArgumentException("a solicitation with no makers asks nobody anything");
    }
    makers.forEach(maker -> answers.put(maker, Answer.PENDING));
  }

  public RequestId request() {
    return request;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Set<ParticipantId> makers() {
    return java.util.Collections.unmodifiableSet(answers.keySet());
  }

  public Optional<Answer> answerFrom(ParticipantId maker) {
    return Optional.ofNullable(answers.get(maker));
  }

  public boolean wasSolicited(ParticipantId maker) {
    return answers.containsKey(maker);
  }

  /** Records that this maker has quoted. Unsolicited makers are ignored rather than rejected. */
  public void quoted(ParticipantId maker) {
    answers.computeIfPresent(maker, (who, was) -> Answer.QUOTED);
  }

  /**
   * Records that this maker will not quote.
   *
   * @return true when that was the last maker still to answer, so nobody is left to wait for
   */
  public boolean declined(ParticipantId maker) {
    if (answers.replace(maker, Answer.DECLINED) == null) {
      throw new IllegalArgumentException(maker + " was not solicited for " + request);
    }
    return answers.values().stream().allMatch(answer -> answer == Answer.DECLINED);
  }

  /** True once the makers have been told the taker's interest ended, so they are told only once. */
  public boolean markInterestGone() {
    if (interestGone) {
      return false;
    }
    interestGone = true;
    return true;
  }

  public boolean hasExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }
}
