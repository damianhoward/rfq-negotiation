package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.Quote;
import com.damianhoward.rfq.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The overlaps — what happens when several things are true at once.
 *
 * <p>The happy path is not what makes this difficult. Every participant may act at any instant,
 * several takers and makers are live together, and four clocks are running.
 */
class OverlapTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("a taker who accepts a price already gone gets the ordinary degradation, not a special event")
  void acceptingAPriceThatWentIsJustADegradation() {
    takerAsksForAPrice();
    var best = makerQuotes("q2", MM2, "3900", "4100");
    makerQuotes("q3", MM3, "3850", "4150");
    tickTo(seconds(1));
    events.clear();

    // Somebody else lifts the whole of the best offer before the taker's accept lands.
    Quote quote = service.quote(best).orElseThrow();
    var offerLeg = quote.leg(Side.OFFER).orElseThrow();
    book.consume(offerLeg.orderId());
    fill(offerLeg.orderId(), qty(1000), price("4100"), seconds(2));

    tickTo(seconds(3));

    // The taker hears the price moved. There is no rejection path, and none is needed: the client
    // knows it accepted and can say "traded away" over the top of this.
    NegotiationEvent.QuoteDegraded degraded =
        events.to(TAKER, NegotiationEvent.QuoteDegraded.class).stream()
            .filter(event -> event.side() == Side.OFFER)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the offer side to degrade"));
    assertEquals(price("4150"), degraded.top().offer().orElseThrow(), "the next best offer");
    assertTrue(service.request(REQ).orElseThrow().isLive(), "the request survives a miss");
  }

  @Test
  @DisplayName("one leg of a two-way quote trading leaves the other working")
  void oneLegTradingLeavesTheOther() {
    takerAsksForAPrice();
    var id = makerQuotes("q1", MM1, "3900", "4100");
    Quote quote = service.quote(id).orElseThrow();
    var offerLeg = quote.leg(Side.OFFER).orElseThrow();
    events.clear();

    book.consume(offerLeg.orderId());
    fill(offerLeg.orderId(), qty(1000), price("4100"), seconds(2));

    assertTrue(quote.leg(Side.BID).isPresent(), "the bid is the spread the maker came for");
    assertTrue(quote.leg(Side.OFFER).isEmpty());
    assertTrue(quote.isLive(), "a maker with one side working has not finished quoting");
    assertTrue(quote.terminal().isEmpty());

    NegotiationEvent.QuoteTraded traded =
        events.onlyTo(MM1, NegotiationEvent.QuoteTraded.class);
    assertEquals(Side.OFFER, traded.side(), "the maker is told at once, and may re-quote");
  }

  @Test
  @DisplayName("a quote is terminal only once both legs are gone")
  void quoteEndsOnlyWhenBothLegsGo() {
    takerAsksForAPrice();
    var id = makerQuotes("q1", MM1, "3900", "4100");
    Quote quote = service.quote(id).orElseThrow();

    var offerLeg = quote.leg(Side.OFFER).orElseThrow();
    book.consume(offerLeg.orderId());
    fill(offerLeg.orderId(), qty(1000), price("4100"), seconds(2));
    assertTrue(quote.isLive());

    var bidLeg = quote.leg(Side.BID).orElseThrow();
    book.consume(bidLeg.orderId());
    fill(bidLeg.orderId(), qty(1000), price("3900"), seconds(3));

    assertFalse(quote.isLive());
    assertEquals(Quote.Terminal.TRADED, quote.terminal().orElseThrow());
  }

  @Test
  @DisplayName("a partial fill reduces the outstanding and leaves the request open")
  void partialFillLeavesTheRequestOpen() {
    takerAsksForAPrice();
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var counter = service.counterFor(REQ).orElseThrow();
    events.clear();

    fill(counter.orderId(), qty(400), price("4050"), seconds(6));

    NegotiationEvent.TradeOccurred trade =
        events.onlyTo(TAKER, NegotiationEvent.TradeOccurred.class);
    assertEquals(qty(400), trade.filled());
    assertEquals(qty(600), trade.outstanding().orElseThrow(), "600 still to find");
    events.none(TAKER, NegotiationEvent.RequestClosed.class);
    assertTrue(service.request(REQ).orElseThrow().isLive());
    assertTrue(counter.isLive(), "the counter is part-consumed, not finished");
  }

  @Test
  @DisplayName("a maker who was never solicited can hit the counter, and that closes the request")
  void anOutsiderCanCloseTheRequest() {
    takerAsksForAPrice();
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var counter = service.counterFor(REQ).orElseThrow();
    events.clear();

    // OUTSIDER was never solicited and knows nothing of the request. It saw a resting order.
    book.consume(counter.orderId());
    fill(counter.orderId(), qty(1000), price("4050"), seconds(6));

    events.onlyTo(TAKER, NegotiationEvent.RequestClosed.class);
    assertFalse(service.request(REQ).orElseThrow().isLive(),
        "the taker asked for size, not for size from a particular party");
    assertFalse(service.solicitation(REQ).orElseThrow().wasSolicited(OUTSIDER));
  }

  @Test
  @DisplayName("a new counter supersedes the old one, and takes its order off the book")
  void aSecondCounterSupersedesTheFirst() {
    takerAsksForAPrice();
    makerQuotes("q2", MM2, "3900", "4100");
    takerCounters("c1", Side.OFFER, "4050", 1000, seconds(5));
    var first = service.counterFor(REQ).orElseThrow();

    takerCounters("c2", Side.OFFER, "4040", 1000, seconds(6));
    var second = service.counterFor(REQ).orElseThrow();

    assertFalse(first.isLive(), "a taker showing two prices at once offers what they did not mean");
    assertFalse(book.isResting(first.orderId()));
    assertTrue(second.isLive());
    assertTrue(book.isResting(second.orderId()));
  }

  @Test
  @DisplayName("a fill on an order this service never placed is ignored")
  void ignoresFillsThatAreNotOurs() {
    takerAsksForAPrice();
    events.clear();

    fill(new com.damianhoward.rfq.model.OrderId(9999), qty(50), price("4000"),
        seconds(2));

    events.none(NegotiationEvent.TradeOccurred.class);
    assertEquals(SIZE, service.request(REQ).orElseThrow().outstanding().orElseThrow());
  }
}
