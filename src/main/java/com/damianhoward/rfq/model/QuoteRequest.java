package com.damianhoward.rfq.model;

import java.time.Instant;
import java.util.Optional;

/**
 * The taker's ask — instrument and size, and deliberately <b>no side</b>.
 *
 * <p>Asking for one side tells the maker which way you are going before they price it, so a request
 * names what and how much and nothing else. The taker's direction is revealed when they counter,
 * which is also the moment they commit; those coinciding is what keeps the negotiation blind on
 * both sides until someone means it.
 *
 * <p>This is the <i>inbound</i> request. The solicitation sent to makers is a separate object with
 * its own clock — see {@link Solicitation} — because the taker's interest can end while the
 * solicitation is still open, and one object shown to both sides could not express that.
 *
 * <p>It tracks exactly one thing: <b>how much is still outstanding</b>. That decides both of its
 * interesting events. Any trade reducing the outstanding is a trade occurring within the request;
 * reaching zero closes it. Who filled it does not enter into it — a maker who was never solicited,
 * hitting the taker's counter, closes the request exactly as a solicited one would, because the
 * taker asked for size and not for size from a particular party.
 */
public final class QuoteRequest {

  private final RequestId id;
  private final ParticipantId taker;
  private final Instrument instrument;
  private final Quantity asked;
  private Quantity outstanding;

  /**
   * How much of the outstanding is already working on the book in the taker's name.
   *
   * <p>Outstanding on its own cannot answer "may this be committed", because an order resting
   * against this request has filled nothing yet and has still spent the requirement.
   */
  private Quantity committed;
  private Instant expiresAt;
  private boolean expiryWarned;
  private Terminal terminal;

  /** How a request ended. */
  public enum Terminal {
    /** Outstanding reached zero — the taker got what they asked for. */
    CLOSED,
    /** The taker withdrew. */
    CANCELLED,
    /** The clock ran out. */
    EXPIRED
  }

  public QuoteRequest(
      RequestId id,
      ParticipantId taker,
      Instrument instrument,
      Quantity asked,
      Instant expiresAt) {
    this.id = java.util.Objects.requireNonNull(id, "id");
    this.taker = java.util.Objects.requireNonNull(taker, "taker");
    this.instrument = java.util.Objects.requireNonNull(instrument, "instrument");
    this.asked = java.util.Objects.requireNonNull(asked, "asked");
    this.outstanding = asked;
    this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public RequestId id() {
    return id;
  }

  public ParticipantId taker() {
    return taker;
  }

  public Instrument instrument() {
    return instrument;
  }

  public Quantity asked() {
    return asked;
  }

  /** What is still to find, or empty once the taker is done. */
  public Optional<Quantity> outstanding() {
    return Optional.ofNullable(outstanding);
  }

  /** What is working on the book against this request, if anything. */
  public Optional<Quantity> committed() {
    return Optional.ofNullable(committed);
  }

  /** What may still be committed: the outstanding, less what is already working. */
  public Optional<Quantity> uncommitted() {
    if (outstanding == null) {
      return Optional.empty();
    }
    return committed == null ? Optional.of(outstanding) : outstanding.minus(committed);
  }

  /** Whether {@code amount} would fit in what is left uncommitted. */
  public boolean hasRoomFor(Quantity amount) {
    return uncommitted().filter(room -> amount.compareTo(room) <= 0).isPresent();
  }

  /**
   * Reserves part of the outstanding against an order about to rest in the book.
   *
   * @throws IllegalArgumentException if there is not that much room; callers check first and tell
   *     the taker, so reaching this is a defect rather than a rejected request
   */
  public void commit(Quantity amount) {
    if (!hasRoomFor(amount)) {
      throw new IllegalArgumentException(
          "request " + id + " has " + uncommitted().map(Object::toString).orElse("0")
              + " uncommitted, asked to commit " + amount);
    }
    committed = committed == null ? amount : new Quantity(committed.units() + amount.units());
  }

  /** Gives back a reservation whose order has stopped working. */
  public void release(Quantity amount) {
    if (committed != null) {
      committed = committed.minus(amount).orElse(null);
    }
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public boolean isLive() {
    return terminal == null;
  }

  public Optional<Terminal> terminal() {
    return Optional.ofNullable(terminal);
  }

  /**
   * Records a trade against this request and returns true when it closed the request.
   *
   * <p>The caller publishes a trade-occurred either way; the return value says whether a close
   * follows it.
   */
  public boolean tradeOccurred(Quantity filled) {
    requireLive();
    if (outstanding == null) {
      throw new IllegalStateException("request " + id + " has nothing outstanding to fill");
    }
    // A fill turns a reservation into a trade, so it stops standing for size still to come.
    release(filled);
    outstanding = outstanding.minus(filled).orElse(null);
    if (outstanding == null) {
      terminal = Terminal.CLOSED;
      return true;
    }
    return false;
  }

  public void cancelled() {
    requireLive();
    terminal = Terminal.CANCELLED;
  }

  public void expired() {
    requireLive();
    terminal = Terminal.EXPIRED;
  }

  /**
   * Moves the deadline out, which is a fact worth publishing rather than a timer adjusted quietly.
   *
   * <p>Resetting also re-arms the warning: a taker whose request was about to lapse and now is not
   * should be warned again when it next approaches, or the second approach passes in silence.
   */
  public void resetLifespan(Instant newExpiry) {
    requireLive();
    this.expiresAt = java.util.Objects.requireNonNull(newExpiry, "newExpiry");
    this.expiryWarned = false;
  }

  public boolean hasExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }

  /**
   * True the first time this request is inside {@code warning} of its deadline.
   *
   * <p>Expiring is a warning with time still on the clock; expired is a fact after it. Only a
   * request has both, because it is the only thing here whose owner can act in that moment —
   * extend, cancel, or take what is on the screen. It answers true once, because a warning repeated
   * every poll is not a warning.
   */
  public boolean shouldWarnAt(Instant now, java.time.Duration warning) {
    if (expiryWarned || terminal != null || hasExpiredAt(now)) {
      return false;
    }
    if (now.isBefore(expiresAt.minus(warning))) {
      return false;
    }
    expiryWarned = true;
    return true;
  }

  private void requireLive() {
    if (terminal != null) {
      throw new IllegalStateException("request " + id + " already ended as " + terminal);
    }
  }
}
