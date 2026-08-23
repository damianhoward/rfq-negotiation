package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.QuoteId;
import com.damianhoward.rfq.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The flow: ask, solicit, quote, improve, counter, trade, close. */
class HappyPathTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("the taker is shown the book before any maker has answered")
  void showsTopOfBookImmediately() {
    takerAsksForAPrice();

    NegotiationEvent.QuoteShown shown =
        events.onlyTo(TAKER, NegotiationEvent.QuoteShown.class);
    assertTrue(shown.top().bid().isEmpty(), "an empty book has no bid to show");
    assertTrue(shown.top().offer().isEmpty());
    assertEquals(0, book.restingCount(), "showing a price must not add liquidity");
  }

  @Test
  @DisplayName("every solicited maker is asked, and the ask carries no side")
  void solicitsEveryMaker() {
    takerAsksForAPrice();

    for (var maker : MAKERS) {
      NegotiationEvent.Solicited asked =
          events.onlyTo(maker, NegotiationEvent.Solicited.class);
      assertEquals(SIZE, asked.size());
      assertEquals(BTC, asked.instrument());
    }
    // The taker is not one of the makers, and must not be asked to price their own request.
    events.none(TAKER, NegotiationEvent.Solicited.class);
  }

  @Test
  @DisplayName("a maker's quote rests both legs and is acknowledged by its own created event")
  void quoteRestsBothLegs() {
    takerAsksForAPrice();
    events.clear();

    QuoteId id = makerQuotes("q1", MM1, "3900", "4100");

    assertEquals(2, book.restingCount(), "a two-way quote is two orders");
    NegotiationEvent.QuoteCreated created =
        events.onlyTo(MM1, NegotiationEvent.QuoteCreated.class);
    assertEquals(id, created.quote());
    events.none(NegotiationEvent.QuoteRejected.class);
  }

  @Test
  @DisplayName("the best of several quotes is what the taker is told about")
  void tellsTheTakerAboutTheBestPrice() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    makerQuotes("q3", MM3, "3850", "4150");
    events.clear();

    tickTo(seconds(1));

    var improvements = events.to(TAKER, NegotiationEvent.QuoteImproved.class);
    assertEquals(2, improvements.size(), "both sides of the market appeared");
    var top = improvements.getFirst().top();
    assertEquals(price("3900"), top.bid().orElseThrow(), "best bid is the highest");
    assertEquals(price("4100"), top.offer().orElseThrow(), "best offer is the lowest");
  }

  @Test
  @DisplayName("a counter rests in the book as the taker's own order")
  void counterRests() {
    takerAsksForAPrice();
    makerQuotes("q2", MM2, "3900", "4100");
    events.clear();

    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));

    assertEquals(3, book.restingCount(), "two quote legs plus the counter");
    var counter = service.counterFor(REQ).orElseThrow();
    assertTrue(counter.isLive());
    assertEquals(price("4050"), counter.price());
  }

  @Test
  @DisplayName("a fill reduces the outstanding, and reaching zero closes the request")
  void fillClosesTheRequest() {
    takerAsksForAPrice();
    makerQuotes("q2", MM2, "3900", "4100");
    var counterId = takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var counter = service.counterFor(REQ).orElseThrow();
    events.clear();

    book.consume(counter.orderId());
    service.filled(counter.orderId(), qty(1000), price("4050"), seconds(6));

    NegotiationEvent.TradeOccurred trade =
        events.onlyTo(TAKER, NegotiationEvent.TradeOccurred.class);
    assertEquals(qty(1000), trade.filled());
    assertTrue(trade.outstanding().isEmpty(), "nothing left to find");
    events.onlyTo(TAKER, NegotiationEvent.RequestClosed.class);
    assertFalse(service.request(REQ).orElseThrow().isLive());
    assertEquals(counterId, counter.id());
  }

  @Test
  @DisplayName("makers who did not get the trade are told somebody else did")
  void tellsTheOtherMakersDoneAway() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var counter = service.counterFor(REQ).orElseThrow();
    events.clear();

    book.consume(counter.orderId());
    service.filled(counter.orderId(), qty(1000), price("4050"), seconds(6));

    events.onlyTo(MM1, NegotiationEvent.DoneAway.class);
    events.onlyTo(MM2, NegotiationEvent.DoneAway.class);
    // MM3 never quoted, so there is nothing it lost.
    events.none(MM3, NegotiationEvent.DoneAway.class);
  }
}
