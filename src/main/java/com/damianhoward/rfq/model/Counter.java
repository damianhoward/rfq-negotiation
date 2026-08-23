package com.damianhoward.rfq.model;

import java.time.Instant;
import java.util.Optional;

/**
 * The taker's counter — a third aggregate, not "another order".
 *
 * <p>It has a lifecycle of its own alongside the request and the quote, and most of what makes
 * negotiation harder than the happy path lives here. It can be improved against, rejected, and lose
 * interest independently of the request that produced it.
 *
 * <p><b>A counter is liquidity, not a message.</b> Once it rests, any maker can hit it — including
 * one who was never solicited and knows nothing of the request. It is not addressed to anyone,
 * which is easy to miss because the word <i>counter</i> implies a reply to a particular party. What
 * the best-price maker gets is a head start on <em>hearing</em> about it, not exclusive access to
 * it.
 *
 * <p>Unlike the request and the quote it is <b>one-sided</b>: countering is where a taker shows
 * their direction, and where they commit.
 */
public final class Counter {

  private final CounterId id;
  private final RequestId request;
  private final ParticipantId taker;
  private final Side side;
  private final Price price;
  private final OrderId orderId;
  private final Instant expiresAt;
  private Quantity remaining;
  private Terminal terminal;

  /** How a counter ended. */
  public enum Terminal {
    TRADED,
    CANCELLED,
    EXPIRED,
    /** Superseded: the taker replaced it with another. */
    CLOSED
  }

  public Counter(
      CounterId id,
      RequestId request,
      ParticipantId taker,
      Side side,
      Price price,
      Quantity size,
      OrderId orderId,
      Instant expiresAt) {
    this.id = java.util.Objects.requireNonNull(id, "id");
    this.request = java.util.Objects.requireNonNull(request, "request");
    this.taker = java.util.Objects.requireNonNull(taker, "taker");
    this.side = java.util.Objects.requireNonNull(side, "side");
    this.price = java.util.Objects.requireNonNull(price, "price");
    this.remaining = java.util.Objects.requireNonNull(size, "size");
    this.orderId = java.util.Objects.requireNonNull(orderId, "orderId");
    this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public CounterId id() {
    return id;
  }

  public RequestId request() {
    return request;
  }

  public ParticipantId taker() {
    return taker;
  }

  public Side side() {
    return side;
  }

  public Price price() {
    return price;
  }

  public OrderId orderId() {
    return orderId;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Quantity> remaining() {
    return Optional.ofNullable(remaining);
  }

  public boolean isLive() {
    return terminal == null;
  }

  public Optional<Terminal> terminal() {
    return Optional.ofNullable(terminal);
  }

  /** Applies a fill. The counter ends only once nothing is left working. */
  public void filled(Quantity amount) {
    requireLive();
    if (remaining == null) {
      throw new IllegalStateException("counter " + id + " has nothing left to fill");
    }
    remaining = remaining.minus(amount).orElse(null);
    if (remaining == null) {
      terminal = Terminal.TRADED;
    }
  }

  public void cancelled() {
    requireLive();
    remaining = null;
    terminal = Terminal.CANCELLED;
  }

  public void expired() {
    requireLive();
    remaining = null;
    terminal = Terminal.EXPIRED;
  }

  /** Replaced by a newer counter from the same taker. */
  public void superseded() {
    requireLive();
    remaining = null;
    terminal = Terminal.CLOSED;
  }

  public boolean hasExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }

  private void requireLive() {
    if (terminal != null) {
      throw new IllegalStateException("counter " + id + " already ended as " + terminal);
    }
  }
}
