package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The maker who made the best price hears about the counter first.
 *
 * <p>Priority here is about awareness, not access: the counter is resting in a public book and
 * anyone may hit it at any moment. What the head start changes is who knows to — which is real,
 * because makers act on the events they are sent rather than by watching depth.
 */
class CounterPriorityTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("the best-priced maker hears about the counter immediately, the others do not")
  void bestMakerHearsFirst() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100"); // best offer, so the taker's buy-side counter is theirs
    makerQuotes("q3", MM3, "3850", "4150");
    events.clear();

    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));

    events.onlyTo(MM2, NegotiationEvent.CounterImproved.class);
    events.none(MM1, NegotiationEvent.CounterImproved.class);
    events.none(MM3, NegotiationEvent.CounterImproved.class);
  }

  @Test
  @DisplayName("the other makers hear once the head start has elapsed")
  void othersHearAfterTheHeadStart() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    makerQuotes("q3", MM3, "3850", "4150");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    events.clear();

    tickTo(seconds(9)); // still inside the ten-second head start
    events.none(MM1, NegotiationEvent.CounterImproved.class);

    tickTo(seconds(16));
    NegotiationEvent.CounterImproved late =
        events.onlyTo(MM1, NegotiationEvent.CounterImproved.class);
    assertEquals(price("4050"), late.price());
    events.onlyTo(MM3, NegotiationEvent.CounterImproved.class);
  }

  @Test
  @DisplayName("a counter pulled inside the head start is never sent on")
  void aWithdrawnCounterIsNotDisseminated() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    events.clear();

    // The taker walks away five seconds in — inside the window, before MM1 was due to hear.
    service.cancelRequest(REQ);
    tickTo(seconds(20));

    events.none(MM1, NegotiationEvent.CounterImproved.class);
  }

  @Test
  @DisplayName("a counter replaced inside the head start sends the new one, never the old")
  void aSupersededCounterIsNotDisseminated() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    events.clear();

    takerCounters("c2", Side.OFFER, "4040", 1000, seconds(8));
    tickTo(seconds(30));

    // MM1 hears once, and about the price that is actually resting.
    NegotiationEvent.CounterImproved heard =
        events.onlyTo(MM1, NegotiationEvent.CounterImproved.class);
    assertEquals(price("4040"), heard.price(),
        "a queued send re-checks the counter when it fires, not when it was queued");
  }

  @Test
  @DisplayName("a counter filled inside the head start is not announced to anyone else")
  void aFilledCounterIsNotDisseminated() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var counter = service.counterFor(REQ).orElseThrow();
    events.clear();

    book.consume(counter.orderId());
    fill(counter.orderId(), qty(1000), price("4050"), seconds(6));
    tickTo(seconds(30));

    events.none(MM1, NegotiationEvent.CounterImproved.class);
    assertTrue(service.request(REQ).orElseThrow().terminal().isPresent());
  }

  @Test
  @DisplayName("with nobody quoting, the counter still goes out to every solicited maker")
  void withNoQuotesEveryoneHearsAfterTheDelay() {
    takerAsksForAPrice();
    events.clear();

    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    // No maker has quoted, so there is no best price and nobody has earned a head start.
    events.none(NegotiationEvent.CounterImproved.class);

    tickTo(seconds(16));
    for (var maker : MAKERS) {
      events.onlyTo(maker, NegotiationEvent.CounterImproved.class);
    }
  }
}
