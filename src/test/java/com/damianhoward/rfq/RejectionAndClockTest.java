package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.QuoteId;
import com.damianhoward.rfq.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The answers that are not "yes", and the two clocks a participant can be told about.
 *
 * <p>Every one of these is a case where the service has to say something rather than stay quiet.
 * Silence is the failure mode that costs a participant real money, because a maker whose price is
 * not working cannot tell that from a maker whose price is working and untouched.
 */
class RejectionAndClockTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("a crossed quote is rejected, and nothing of it reaches the book")
  void crossedQuoteIsRejected() {
    takerAsksForAPrice();
    events.clear();

    var accepted = service.quote(
        new QuoteId("bad"), REQ, MM1, price("4200"), price("3900"), SIZE, seconds(30));

    assertTrue(accepted.isEmpty(), "a maker bidding above their own offer has not made a market");
    assertEquals(0, book.restingCount(), "a rejected quote rests no legs, not even the good one");
    events.none(MM1, NegotiationEvent.QuoteCreated.class);
    NegotiationEvent.QuoteRejected rejected =
        events.onlyTo(MM1, NegotiationEvent.QuoteRejected.class);
    assertEquals(REQ, rejected.request());
    assertTrue(rejected.reason().contains("4200"), "the reason names the price that was wrong");
  }

  @Test
  @DisplayName("a quote exactly at the touch on both sides is rejected — a zero spread is crossed enough")
  void zeroWidthQuoteIsRejected() {
    takerAsksForAPrice();
    events.clear();

    assertTrue(
        service.quote(new QuoteId("flat"), REQ, MM1, price("4000"), price("4000"), SIZE,
            seconds(30)).isEmpty());
    events.onlyTo(MM1, NegotiationEvent.QuoteRejected.class);
  }

  @Test
  @DisplayName("moving the deadline is published, because a clock changed in silence cannot be reconciled")
  void lifespanResetIsPublished() {
    takerAsksForAPrice();
    tickTo(seconds(35));
    events.onlyTo(TAKER, NegotiationEvent.RequestExpiring.class);
    events.clear();

    service.resetLifespan(REQ, seconds(300));

    events.onlyTo(TAKER, NegotiationEvent.RequestLifespanReset.class);
    tickTo(seconds(40));
    events.none(TAKER, NegotiationEvent.RequestExpiring.class);
    assertTrue(service.request(REQ).orElseThrow().isLive(), "the deadline moved, so it did not lapse");
  }

  @Test
  @DisplayName("the makers hear when the counter they were shown stops being the best price")
  void makersHearTheCounterBeingBettered() {
    takerAsksForAPrice();
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(1));
    tickTo(seconds(2));
    events.clear();

    // A maker offers below the taker's counter, so the counter is no longer the best offer.
    makerQuotes("q9", MM3, "3800", "4000", 1000, seconds(3));
    tickTo(seconds(4));

    for (var maker : MAKERS) {
      NegotiationEvent.CounterDegraded degraded =
          events.onlyTo(maker, NegotiationEvent.CounterDegraded.class);
      assertEquals(Side.OFFER, degraded.side());
      assertEquals(REQ, degraded.request());
    }

    // Repeating it every poll would be noise, not news.
    events.clear();
    tickTo(seconds(5));
    events.none(NegotiationEvent.CounterDegraded.class);
  }

  @Test
  @DisplayName("identifiers and sizes refuse the values that would travel a long way before failing")
  void valueTypesRejectNonsense() {
    assertThrows(IllegalArgumentException.class,
        () -> new com.damianhoward.rfq.model.ParticipantId(" "));
    assertThrows(IllegalArgumentException.class,
        () -> new com.damianhoward.rfq.model.RequestId(""));
    assertThrows(IllegalArgumentException.class,
        () -> new com.damianhoward.rfq.model.QuoteId(""));
    assertThrows(IllegalArgumentException.class,
        () -> new com.damianhoward.rfq.model.CounterId(""));
    assertThrows(IllegalArgumentException.class,
        () -> new com.damianhoward.rfq.model.Instrument(" "));
    assertThrows(IllegalArgumentException.class, () -> qty(0));
    assertThrows(IllegalArgumentException.class, () -> qty(-5));
    assertThrows(IllegalArgumentException.class, () -> new com.damianhoward.rfq.model.Price(-1));

    assertEquals("taker-1", TAKER.toString());
    assertEquals("BTC", BTC.toString());
    assertEquals("req-1", REQ.toString());
    assertEquals("1000", SIZE.toString());
    assertEquals("4000.5", price("4000.5").toString());
  }

  @Test
  @DisplayName("a price is better or worse depending which side is asking")
  void betternessIsRelativeToTheSide() {
    assertTrue(price("4100").betterThan(price("4000"), Side.BID), "a buyer prefers to pay more");
    assertTrue(price("4000").betterThan(price("4100"), Side.OFFER), "a seller prefers to ask less");
    assertEquals(SIZE, SIZE.min(qty(5000)));
    assertEquals(qty(5), qty(5).min(SIZE));
  }
}
