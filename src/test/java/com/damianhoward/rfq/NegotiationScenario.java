package com.damianhoward.rfq;

import com.damianhoward.rfq.model.CounterId;
import com.damianhoward.rfq.model.ExecutionId;
import com.damianhoward.rfq.model.OrderId;
import com.damianhoward.rfq.model.Instrument;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.model.Price;
import com.damianhoward.rfq.model.Quantity;
import com.damianhoward.rfq.model.QuoteId;
import com.damianhoward.rfq.model.RequestId;
import com.damianhoward.rfq.model.Side;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * The cast and the clock every negotiation test shares.
 *
 * <p>Time is explicit and never {@code now()}: expiry, the counter head start and the expiry
 * warning are all deadlines, and a test that reads the wall clock passes or fails on when it
 * happened to run. Everything here is relative to {@link #START}.
 */
abstract class NegotiationScenario {

  static final Instant START = Instant.parse("2026-01-05T09:00:00Z");

  static final Instrument BTC = new Instrument("BTC");

  static final ParticipantId TAKER = new ParticipantId("taker-1");
  static final ParticipantId OTHER_TAKER = new ParticipantId("taker-2");
  static final ParticipantId MM1 = new ParticipantId("mm-1");
  static final ParticipantId MM2 = new ParticipantId("mm-2");
  static final ParticipantId MM3 = new ParticipantId("mm-3");

  /** A maker who was never solicited, and can still hit anything resting. */
  static final ParticipantId OUTSIDER = new ParticipantId("mm-outsider");

  static final Set<ParticipantId> MAKERS = Set.of(MM1, MM2, MM3);

  static final RequestId REQ = new RequestId("req-1");
  static final Quantity SIZE = Quantity.of(1000);

  /** The taker's own clock: how long they will wait. */
  static final Duration REQUEST_LIFE = Duration.ofMinutes(1);

  /** The system's, and deliberately longer — liquidity is worth gathering past the taker's patience. */
  static final Duration SOLICITATION_LIFE = Duration.ofMinutes(10);

  static final Duration QUOTE_LIFE = Duration.ofSeconds(30);

  private int executions;

  FakeOrderBook book;
  RecordingEvents events;
  NegotiationService service;

  void newScenario() {
    book = new FakeOrderBook();
    events = new RecordingEvents();
    service = new NegotiationService(book, events);
    at(START);
  }

  /** Moves the shared clock and lets the service act on it. */
  void tickTo(Instant instant) {
    at(instant);
    service.poll(instant);
  }

  /** Moves the clock without polling, for asserting that nothing depends on the sweep. */
  void at(Instant instant) {
    book.clockAt(instant);
  }

  /** Reports a fill with a fresh execution id, which is the ordinary case. */
  void fill(OrderId orderId, Quantity amount, Price price, Instant at) {
    service.filled(nextExecution(), orderId, amount, price, at);
  }

  /** A distinct execution every time, so no test accidentally relies on the de-duplication. */
  ExecutionId nextExecution() {
    return new ExecutionId("exec-" + (++executions));
  }

  static Instant seconds(long fromStart) {
    return START.plusSeconds(fromStart);
  }

  static Price price(String value) {
    return Price.of(value);
  }

  static Quantity qty(long units) {
    return Quantity.of(units);
  }

  /** Opens the standard request: 1,000 BTC, no side, three makers solicited. */
  void takerAsksForAPrice() {
    service.requestQuote(
        REQ,
        TAKER,
        BTC,
        SIZE,
        START.plus(REQUEST_LIFE),
        MAKERS,
        START.plus(SOLICITATION_LIFE));
  }

  /** A two-way quote from one maker, valid for {@link #QUOTE_LIFE} from the given instant. */
  QuoteId makerQuotes(
      String id, ParticipantId maker, String bid, String offer, long size, Instant from) {
    return service.quote(
            new QuoteId(id),
            REQ,
            maker,
            price(bid),
            price(offer),
            qty(size),
            from.plus(QUOTE_LIFE))
        .orElseThrow(() -> new AssertionError("quote " + id + " was rejected"));
  }

  QuoteId makerQuotes(String id, ParticipantId maker, String bid, String offer) {
    return makerQuotes(id, maker, bid, offer, SIZE.units(), START);
  }

  /** The taker counters, one-sided, valid to the end of their request. */
  CounterId takerCounters(String id, Side side, String at, long size, Instant now) {
    return service
        .counter(new CounterId(id), REQ, side, price(at), qty(size), START.plus(REQUEST_LIFE), now)
        .orElseThrow(() -> new AssertionError("counter " + id + " was refused"));
  }
}
