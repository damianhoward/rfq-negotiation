package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.CounterId;
import com.damianhoward.rfq.model.OrderId;
import com.damianhoward.rfq.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much a taker may have working at once, and what happens when the same fill arrives twice.
 *
 * <p>Both of these are ways of filling a taker for more than they asked for, which is the worst
 * thing this service can do. A missed price costs an opportunity; an unrequested position costs
 * money and has to be unwound in the market that just moved.
 */
class CommitmentTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("a counter for the whole size leaves no room to accept a maker's price as well")
  void counterAndAcceptCannotBothCommitTheWholeSize() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));
    events.clear();

    var accepted = service.accept(REQ, Side.BID, price("4100"), SIZE);

    assertTrue(accepted.isEmpty(), "one requirement cannot be committed twice");
    NegotiationEvent.CommitmentRejected refused =
        events.onlyTo(TAKER, NegotiationEvent.CommitmentRejected.class);
    assertEquals(SIZE, refused.wanted());
    assertEquals(3, book.restingCount(),
        "the maker's two legs and the counter still resting, and nothing at all from the accept");
  }

  @Test
  @DisplayName("pulling the counter gives the size back, and the accept then goes through")
  void withdrawingTheCounterFreesTheSize() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));
    assertTrue(service.accept(REQ, Side.BID, price("4100"), SIZE).isEmpty());

    // The taker replaces the counter with a smaller one, freeing the difference.
    takerCounters("c2", Side.OFFER, "4050", 400, seconds(2));
    events.clear();

    var accepted = service.accept(REQ, Side.BID, price("4100"), qty(600));

    assertTrue(accepted.isPresent(), "600 uncommitted, and 600 was asked for");
    events.none(TAKER, NegotiationEvent.CommitmentRejected.class);
    assertEquals(qty(400), service.request(REQ).orElseThrow().committed().orElseThrow());
  }

  @Test
  @DisplayName("a counter larger than what is left is refused, and the old one is not lost with it")
  void anOversizedCounterIsRefused() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", 400, seconds(1));
    var accepted = service.accept(REQ, Side.BID, price("4100"), qty(600));
    assertTrue(accepted.isPresent());
    fill(accepted.orElseThrow(), qty(600), price("4100"), seconds(2));
    events.clear();

    // 400 outstanding, all of it committed to the counter already resting.
    var refused =
        service.counter(
            new CounterId("c2"), REQ, Side.OFFER, price("4040"), qty(1000), seconds(90),
            seconds(3));

    assertTrue(refused.isEmpty());
    NegotiationEvent.CommitmentRejected rejected =
        events.onlyTo(TAKER, NegotiationEvent.CommitmentRejected.class);
    assertEquals(qty(400), rejected.available(), "the taker is told the number they can act on");
  }

  @Test
  @DisplayName("a counter releases what it was holding when it expires")
  void anExpiredCounterGivesItsSizeBack() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));
    assertEquals(SIZE, service.request(REQ).orElseThrow().committed().orElseThrow());

    tickTo(seconds(90)); // past the counter's own deadline, inside the request's

    assertTrue(service.request(REQ).orElseThrow().committed().isEmpty(),
        "a counter that is no longer working is not spending the requirement");
  }

  @Test
  @DisplayName("a partial fill releases only what filled, and the rest stays committed")
  void aPartialFillReleasesOnlyItsOwnSize() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));

    fill(service.counterFor(REQ).orElseThrow().orderId(), qty(400), price("4050"), seconds(2));

    var request = service.request(REQ).orElseThrow();
    assertEquals(qty(600), request.outstanding().orElseThrow());
    assertEquals(qty(600), request.committed().orElseThrow(), "600 of the counter is still resting");
    assertTrue(request.uncommitted().isEmpty(), "and so there is nothing free to commit elsewhere");
  }

  @Test
  @DisplayName("the same execution delivered twice fills the request once")
  void aRedeliveredFillIsCountedOnce() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));
    OrderId orderId = service.counterFor(REQ).orElseThrow().orderId();
    var execution = nextExecution();
    events.clear();

    service.filled(execution, orderId, qty(400), price("4050"), seconds(2));
    service.filled(execution, orderId, qty(400), price("4050"), seconds(2));

    assertEquals(1, events.to(TAKER, NegotiationEvent.TradeOccurred.class).size(),
        "the transport redelivered; the taker did not trade twice");
    assertEquals(qty(600), service.request(REQ).orElseThrow().outstanding().orElseThrow());
  }

  @Test
  @DisplayName("two genuine fills of the same size are both counted, which is why order id is not enough")
  void twoRealFillsOfEqualSizeBothCount() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));
    OrderId orderId = service.counterFor(REQ).orElseThrow().orderId();
    events.clear();

    fill(orderId, qty(400), price("4050"), seconds(2));
    fill(orderId, qty(400), price("4050"), seconds(3));

    assertEquals(2, events.to(TAKER, NegotiationEvent.TradeOccurred.class).size());
    assertEquals(qty(200), service.request(REQ).orElseThrow().outstanding().orElseThrow());
  }

  @Test
  @DisplayName("closing the request releases the counter's reservation with it")
  void closingReleasesEverything() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", SIZE.units(), seconds(1));

    fill(service.counterFor(REQ).orElseThrow().orderId(), SIZE, price("4050"), seconds(2));

    var request = service.request(REQ).orElseThrow();
    assertFalse(request.isLive());
    assertTrue(request.committed().isEmpty());
    assertTrue(request.uncommitted().isEmpty());
  }
}
