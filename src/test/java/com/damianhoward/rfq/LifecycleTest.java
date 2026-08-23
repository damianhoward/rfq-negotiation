package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.Quote;
import com.damianhoward.rfq.model.QuoteRequest;
import com.damianhoward.rfq.model.Side;
import com.damianhoward.rfq.model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How each of the three things ends, and what the other parties hear when it does. */
class LifecycleTest extends NegotiationScenario {

  @BeforeEach
  void setUp() {
    newScenario();
  }

  @Test
  @DisplayName("a quote ends by expiring, and both legs go because both carried the deadline")
  void quoteExpiresBothLegs() {
    takerAsksForAPrice();
    var id = makerQuotes("q1", MM1, "3900", "4100");
    events.clear();

    tickTo(seconds(31)); // past QUOTE_LIFE

    Quote quote = service.quote(id).orElseThrow();
    assertEquals(Quote.Terminal.EXPIRED, quote.terminal().orElseThrow());
    assertTrue(quote.liveLegs().isEmpty());
    events.onlyTo(MM1, NegotiationEvent.QuoteExpired.class);
  }

  @Test
  @DisplayName("a maker pulling a quote takes both legs off the book")
  void cancellingAQuoteRemovesBothLegs() {
    takerAsksForAPrice();
    var id = makerQuotes("q1", MM1, "3900", "4100");
    assertEquals(2, book.restingCount());
    events.clear();

    service.cancelQuote(id);

    assertEquals(0, book.restingCount(), "a cancelled quote leaves nothing behind");
    assertEquals(Quote.Terminal.CANCELLED,
        service.quote(id).orElseThrow().terminal().orElseThrow());
    events.onlyTo(MM1, NegotiationEvent.QuoteCancelled.class);
  }

  @Test
  @DisplayName("a bulk pull takes every one of that maker's quotes and nobody else's")
  void bulkCancelTakesOnlyThatMakersQuotes() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3800", "4200");
    makerQuotes("q1b", MM1, "3790", "4210");
    makerQuotes("q2", MM2, "3900", "4100");
    assertEquals(6, book.restingCount());
    events.clear();

    int pulled = service.cancelAllQuotesOf(MM1);

    assertEquals(2, pulled);
    assertEquals(2, book.restingCount(), "MM2's two legs are untouched");
    assertEquals(2, events.to(MM1, NegotiationEvent.QuoteCancelled.class).size());
    events.none(MM2, NegotiationEvent.QuoteCancelled.class);
  }

  @Test
  @DisplayName("when every solicited maker declines, the taker is told rather than left waiting")
  void allDecliningEndsTheWait() {
    takerAsksForAPrice();
    events.clear();

    service.decline(REQ, MM1);
    service.decline(REQ, MM2);
    events.none(TAKER, NegotiationEvent.AllMakersDeclined.class);

    service.decline(REQ, MM3);

    events.onlyTo(TAKER, NegotiationEvent.AllMakersDeclined.class);
    events.none(TAKER, NegotiationEvent.InterestGone.class);
    assertTrue(service.request(REQ).orElseThrow().isLive(),
        "nobody would quote it, but the taker still wants a price");
  }

  @Test
  @DisplayName("a maker who declines is recorded as such, and one who quotes is not")
  void solicitationRecordsEachAnswer() {
    takerAsksForAPrice();
    service.decline(REQ, MM1);
    makerQuotes("q2", MM2, "3900", "4100");

    var solicitation = service.solicitation(REQ).orElseThrow();
    assertEquals(Solicitationless.DECLINED, map(solicitation.answerFrom(MM1).orElseThrow()));
    assertEquals(Solicitationless.QUOTED, map(solicitation.answerFrom(MM2).orElseThrow()));
    assertEquals(Solicitationless.PENDING, map(solicitation.answerFrom(MM3).orElseThrow()));
  }

  /** Local mirror of the answer enum, so the assertion reads without a long qualified name. */
  private enum Solicitationless {
    PENDING,
    QUOTED,
    DECLINED
  }

  private static Solicitationless map(
      com.damianhoward.rfq.model.Solicitation.Answer answer) {
    return Solicitationless.valueOf(answer.name());
  }

  @Test
  @DisplayName("the taker withdrawing tells the makers, and leaves their quotes working")
  void cancellingTheRequestLeavesQuotesAlone() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3900", "4100");
    events.clear();

    service.cancelRequest(REQ);

    events.onlyTo(TAKER, NegotiationEvent.RequestCancelled.class);
    for (var maker : MAKERS) {
      events.onlyTo(maker, NegotiationEvent.InterestGone.class);
    }
    assertEquals(2, book.restingCount(),
        "liquidity outlives the negotiation that summoned it");
    assertTrue(service.quote(new com.damianhoward.rfq.model.QuoteId("q1")).orElseThrow().isLive());
  }

  @Test
  @DisplayName("the taker's request expires on its own clock, well before the solicitation's")
  void requestExpiresBeforeTheSolicitation() {
    takerAsksForAPrice();
    events.clear();

    tickTo(seconds(61)); // past REQUEST_LIFE, far inside SOLICITATION_LIFE

    QuoteRequest request = service.request(REQ).orElseThrow();
    assertEquals(QuoteRequest.Terminal.EXPIRED, request.terminal().orElseThrow());
    events.onlyTo(TAKER, NegotiationEvent.RequestExpired.class);
    assertFalse(service.solicitation(REQ).orElseThrow().hasExpiredAt(seconds(61)),
        "the system was still gathering liquidity when the taker stopped waiting");
  }

  @Test
  @DisplayName("the taker is warned once before expiry, with time still to act")
  void warnsOnceBeforeExpiry() {
    takerAsksForAPrice();
    events.clear();

    tickTo(seconds(20)); // more than 30s left, no warning yet
    events.none(TAKER, NegotiationEvent.RequestExpiring.class);

    tickTo(seconds(35));
    events.onlyTo(TAKER, NegotiationEvent.RequestExpiring.class);

    tickTo(seconds(40)); // a warning repeated every poll is not a warning
    events.onlyTo(TAKER, NegotiationEvent.RequestExpiring.class);
  }

  @Test
  @DisplayName("an accept is all-or-nothing and rests nothing")
  void acceptIsFillOrKill() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3900", "4100");
    events.clear();

    var orderId = service.accept(REQ, Side.BID, price("4100"), SIZE).orElseThrow();

    assertEquals(TimeInForce.FILL_OR_KILL, book.timeInForceOf(orderId).orElseThrow());
    assertFalse(book.isResting(orderId), "an accept that does not trade leaves nothing behind");
    assertEquals(2, book.restingCount(), "only the maker's two legs");
  }

  @Test
  @DisplayName("an accepted price fills the request the same way a counter would")
  void acceptCreditsTheRequest() {
    takerAsksForAPrice();
    makerQuotes("q1", MM1, "3900", "4100");
    var orderId = service.accept(REQ, Side.BID, price("4100"), SIZE).orElseThrow();
    events.clear();

    fill(orderId, SIZE, price("4100"), seconds(2));

    events.onlyTo(TAKER, NegotiationEvent.TradeOccurred.class);
    events.onlyTo(TAKER, NegotiationEvent.RequestClosed.class);
  }

  @Test
  @DisplayName("resetting the lifespan moves the deadline and re-arms the warning")
  void lifespanResetReArmsTheWarning() {
    takerAsksForAPrice();
    QuoteRequest request = service.request(REQ).orElseThrow();

    tickTo(seconds(35));
    events.clear();

    request.resetLifespan(seconds(180));
    tickTo(seconds(40));
    events.none(TAKER, NegotiationEvent.RequestExpiring.class);

    tickTo(seconds(155)); // inside 30s of the new deadline
    events.onlyTo(TAKER, NegotiationEvent.RequestExpiring.class);
  }
}
