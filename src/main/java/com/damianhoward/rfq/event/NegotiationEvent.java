package com.damianhoward.rfq.event;

import com.damianhoward.rfq.model.CounterId;
import com.damianhoward.rfq.model.Instrument;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.model.Price;
import com.damianhoward.rfq.model.Quantity;
import com.damianhoward.rfq.model.QuoteId;
import com.damianhoward.rfq.model.RequestId;
import com.damianhoward.rfq.model.Side;
import com.damianhoward.rfq.model.TopOfBook;
import java.util.Optional;

/**
 * Everything this service tells a participant.
 *
 * <p>Sealed, so the compiler can say when a handler has stopped covering the domain — a switch over
 * these is exhaustive, and adding an event breaks every consumer that has not decided what to do
 * about it. That is the intended cost.
 *
 * <p>Two things shape the catalogue. <b>Every event names its audience</b>, because the same fact
 * reaches a taker and a maker differently and "who is this for" is not something a transport should
 * have to infer. And <b>events name direction and cause</b> — improved and degraded rather than a
 * generic update, expiring as a warning distinct from expired as a fact, interest-gone saying which
 * side evaporated. Collapse those into "something changed" and the receiver has to re-derive what
 * happened from state it may not have.
 */
public sealed interface NegotiationEvent {

  /** Who should receive this. */
  ParticipantId audience();

  /** The request all of this hangs from. */
  RequestId request();

  // ---- To the taker -------------------------------------------------------------------------

  /**
   * The best bid and offer the book already holds, sent the moment a request arrives.
   *
   * <p>This adds nothing to the book. It creates a <em>baseline</em> — the price this taker has now
   * been told — which is what every later improvement or degradation is measured against.
   */
  record QuoteShown(ParticipantId audience, RequestId request, Instrument instrument, TopOfBook top)
      implements NegotiationEvent {}

  /** A side of the taker's market got better. */
  record QuoteImproved(
      ParticipantId audience, RequestId request, Side side, TopOfBook top)
      implements NegotiationEvent {}

  /**
   * A side of the taker's market got worse.
   *
   * <p>This is also what a taker gets when they accept a price that has already gone: top of book
   * changed, so the notice they would have received anyway carries the new price. The service does
   * nothing special — the client knows it accepted and can say <i>traded away</i> rather than
   * <i>price moved</i>. The event carries the fact; the client carries the context.
   */
  record QuoteDegraded(ParticipantId audience, RequestId request, Side side, TopOfBook top)
      implements NegotiationEvent {}

  /** A trade reduced what the taker still has outstanding. */
  record TradeOccurred(
      ParticipantId audience,
      RequestId request,
      Quantity filled,
      Price price,
      Optional<Quantity> outstanding)
      implements NegotiationEvent {}

  /** The taker's request is within sight of its deadline, with time still to act. */
  record RequestExpiring(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  /** The request's clock moved, published rather than adjusted quietly. */
  record RequestLifespanReset(ParticipantId audience, RequestId request)
      implements NegotiationEvent {}

  /** Outstanding reached zero. The taker got what they asked for. */
  record RequestClosed(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  /** The taker withdrew. */
  record RequestCancelled(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  /** The taker's clock ran out. */
  record RequestExpired(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  // ---- To the makers ------------------------------------------------------------------------

  /** A maker is asked for a price. Carries no side: the taker's direction is not the maker's. */
  record Solicited(
      ParticipantId audience, RequestId request, Instrument instrument, Quantity size)
      implements NegotiationEvent {}

  /** The maker's quote is live. This is also the acknowledgement that it was accepted. */
  record QuoteCreated(ParticipantId audience, RequestId request, QuoteId quote)
      implements NegotiationEvent {}

  /** The maker's quote was refused, and why. The other half of the acknowledgement. */
  record QuoteRejected(ParticipantId audience, RequestId request, String reason)
      implements NegotiationEvent {}

  /** One of the maker's legs traded. The other leg, if any, is still working. */
  record QuoteTraded(
      ParticipantId audience, RequestId request, QuoteId quote, Side side, Quantity filled,
      Price price)
      implements NegotiationEvent {}

  /** The maker pulled it — individually, or in a bulk pull. */
  record QuoteCancelled(ParticipantId audience, RequestId request, QuoteId quote)
      implements NegotiationEvent {}

  /** The quote's validity ran out. */
  record QuoteExpired(ParticipantId audience, RequestId request, QuoteId quote)
      implements NegotiationEvent {}

  /**
   * The taker traded, and not with this maker.
   *
   * <p>Not terminal — their quote is untouched and still working — and not the same fact as the
   * taker's interest ending. One says <i>somebody else got it</i>, the other <i>nobody wants it
   * now</i>, and a maker deciding whether to keep a price showing acts differently on each.
   */
  record DoneAway(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  /**
   * The taker's interest ended while the solicitation was still open.
   *
   * <p>The makers' quotes are untouched: liquidity outlives the negotiation that summoned it, and
   * the next taker can hit it.
   */
  record InterestGone(ParticipantId audience, RequestId request) implements NegotiationEvent {}

  /**
   * Every solicited maker declined. Sent to the taker.
   *
   * <p>Deliberately not the same event as {@link InterestGone}, which it was until a reviewer
   * pointed out they are opposites: there, the taker has stopped wanting a price and the makers are
   * told; here, the taker still wants one and nobody would quote it. Sharing one event made both
   * audiences infer which had happened from their own identity, which is a fact the sender already
   * had and threw away.
   *
   * <p>The request stays live. The taker can widen, counter into the book, or wait, and a maker who
   * declined at one moment may quote a minute later.
   */
  record AllMakersDeclined(ParticipantId audience, RequestId request)
      implements NegotiationEvent {}

  /**
   * A commitment was refused because the request has no room left for it.
   *
   * <p>What is available to commit is the outstanding less whatever is already working on the book
   * in the taker's name. A taker holding a counter for the whole size and then accepting a maker's
   * price for the whole size has asked for one lot and offered to buy two, and both can fill.
   *
   * <p>Refused rather than reduced to fit: a taker asking to commit more than remains meant
   * something different from one asking for what is left, and quietly filling the smaller size
   * hands them a position they did not choose.
   */
  record CommitmentRejected(
      ParticipantId audience,
      RequestId request,
      Quantity wanted,
      Quantity available,
      String reason)
      implements NegotiationEvent {}

  /** The taker's counter is now top of book on its side. */
  record CounterImproved(
      ParticipantId audience, RequestId request, CounterId counter, Side side, Price price,
      Quantity size)
      implements NegotiationEvent {}

  /** The counter has been bettered. */
  record CounterDegraded(ParticipantId audience, RequestId request, CounterId counter, Side side)
      implements NegotiationEvent {}
}
