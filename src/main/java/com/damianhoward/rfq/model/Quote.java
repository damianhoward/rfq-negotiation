package com.damianhoward.rfq.model;

import java.time.Instant;
import java.util.Optional;

/**
 * A maker's price, resting in the book as two orders.
 *
 * <p><b>Every quote is two-way.</b> A taker who asks for one side has already told the maker which
 * way they are going, and a maker who knows that prices accordingly — so a request carries no
 * direction and a quote answers with both. That is a market-structure decision rather than a
 * convenience, and it is why this type holds two legs and never one.
 *
 * <p>The legs share an owner and a deadline and are cancelled together, but trade independently.
 * When one fills the other keeps working: selling at the offer and then buying at the bid is the
 * spread the maker came for. Killing the survivor would quietly redefine a quoted size from
 * <i>either way</i> to <i>in total</i>, which is a different offer from the one the maker made.
 *
 * <p>This is the <i>inbound</i> quote — a maker's, owned by them. The quote a taker is shown is a
 * different thing entirely: a view of top of book whose two sides may come from different makers
 * and which belongs to nobody. See {@link TopOfBook}.
 */
public final class Quote {

  private final QuoteId id;
  private final RequestId request;
  private final ParticipantId maker;
  private final Instrument instrument;
  private final Instant expiresAt;
  private QuoteLeg bid;
  private QuoteLeg offer;
  private Terminal terminal;

  /** How a quote ended. Never none, never two, and the reason matters as much as the fact. */
  public enum Terminal {
    TRADED,
    CANCELLED,
    EXPIRED
  }

  public Quote(
      QuoteId id,
      RequestId request,
      ParticipantId maker,
      Instrument instrument,
      QuoteLeg bid,
      QuoteLeg offer,
      Instant expiresAt) {
    this.id = java.util.Objects.requireNonNull(id, "id");
    this.request = java.util.Objects.requireNonNull(request, "request");
    this.maker = java.util.Objects.requireNonNull(maker, "maker");
    this.instrument = java.util.Objects.requireNonNull(instrument, "instrument");
    this.bid = java.util.Objects.requireNonNull(bid, "bid");
    this.offer = java.util.Objects.requireNonNull(offer, "offer");
    this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
    if (bid.side() != Side.BID || offer.side() != Side.OFFER) {
      throw new IllegalArgumentException("a two-way quote needs one bid leg and one offer leg");
    }
    if (!bid.price().betterThan(offer.price(), Side.OFFER)) {
      throw new IllegalArgumentException(
          "a quote's bid must be below its offer, got " + bid.price() + " / " + offer.price());
    }
  }

  public QuoteId id() {
    return id;
  }

  public RequestId request() {
    return request;
  }

  public ParticipantId maker() {
    return maker;
  }

  public Instrument instrument() {
    return instrument;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  /** The leg still working on this side, or empty once it has fully traded. */
  public Optional<QuoteLeg> leg(Side side) {
    QuoteLeg leg = side == Side.BID ? bid : offer;
    return Optional.ofNullable(leg);
  }

  /** Every leg still resting, which is what a cancellation has to reach. */
  public java.util.List<QuoteLeg> liveLegs() {
    java.util.List<QuoteLeg> legs = new java.util.ArrayList<>(2);
    if (bid != null) {
      legs.add(bid);
    }
    if (offer != null) {
      legs.add(offer);
    }
    return legs;
  }

  public boolean isLive() {
    return terminal == null && !liveLegs().isEmpty();
  }

  public Optional<Terminal> terminal() {
    return Optional.ofNullable(terminal);
  }

  /**
   * Applies a fill against one leg. The other leg is untouched — see the class comment for why.
   *
   * <p>The quote is only {@link Terminal#TRADED} once <em>both</em> legs are gone, because a maker
   * with one side still working has not finished quoting.
   */
  public void filled(Side side, Quantity amount) {
    requireLive();
    QuoteLeg leg = side == Side.BID ? bid : offer;
    if (leg == null) {
      throw new IllegalStateException("quote " + id + " has no live " + side + " leg to fill");
    }
    QuoteLeg left = leg.less(amount).orElse(null);
    if (side == Side.BID) {
      bid = left;
    } else {
      offer = left;
    }
    if (liveLegs().isEmpty()) {
      terminal = Terminal.TRADED;
    }
  }

  /** The maker pulled it, individually or in a bulk pull. Both legs go. */
  public void cancelled() {
    requireLive();
    bid = null;
    offer = null;
    terminal = Terminal.CANCELLED;
  }

  /** Its validity ran out. Both legs go, because both carried the same deadline. */
  public void expired() {
    requireLive();
    bid = null;
    offer = null;
    terminal = Terminal.EXPIRED;
  }

  public boolean hasExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }

  private void requireLive() {
    if (terminal != null) {
      throw new IllegalStateException("quote " + id + " already ended as " + terminal);
    }
  }
}
