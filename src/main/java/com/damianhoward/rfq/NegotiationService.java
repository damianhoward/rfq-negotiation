package com.damianhoward.rfq;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.Counter;
import com.damianhoward.rfq.model.CounterId;
import com.damianhoward.rfq.model.Instrument;
import com.damianhoward.rfq.model.OrderId;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.model.Price;
import com.damianhoward.rfq.model.Quantity;
import com.damianhoward.rfq.model.Quote;
import com.damianhoward.rfq.model.QuoteId;
import com.damianhoward.rfq.model.QuoteLeg;
import com.damianhoward.rfq.model.QuoteRequest;
import com.damianhoward.rfq.model.RequestId;
import com.damianhoward.rfq.model.Side;
import com.damianhoward.rfq.model.Solicitation;
import com.damianhoward.rfq.model.TimeInForce;
import com.damianhoward.rfq.model.TopOfBook;
import com.damianhoward.rfq.port.EventSink;
import com.damianhoward.rfq.port.OrderBook;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The negotiation, coordinated around a book it does not own.
 *
 * <p>Quotes and counters are orders resting in that book, so every trade is a genuine price–time
 * match and there is no second execution path to get subtly wrong. What lives here is the part a
 * book cannot know: who asked, who was asked, how much is still outstanding, what each party was
 * last told, and which of them should hear about a change first.
 *
 * <p><b>The principle that settles close calls is to create the conditions for the most
 * matches.</b> A venue of this kind is paid on the trades that happen in it, so liquidity that
 * could match and does not is what the design exists to avoid. That is why a maker's quote outlives
 * the request that summoned it, why a counter is liquidity anyone may take, and why one leg of a
 * two-way quote trading leaves the other working.
 *
 * <p>Not thread-safe, and deliberately: every method is a whole negotiation operation, and the
 * caller runs them on one thread the way the book runs its own. {@link #poll(Instant)} is the only
 * thing that needs a schedule.
 */
public final class NegotiationService {

  /**
   * How long the best-priced maker keeps the counter to itself before the rest are told.
   *
   * <p>Priority here is about awareness rather than access: anyone may hit the counter at any
   * moment, because it is resting in a public book. What the delay changes is who knows to. Makers
   * act on the events they are sent rather than by watching depth, so telling one first is a real
   * head start, and the maker who produced the best price has earned it. It is soft priority, and
   * calling it soft is the honest description.
   */
  public static final Duration COUNTER_HEAD_START = Duration.ofSeconds(10);

  /** How close to its deadline a request gets before its owner is warned, with time still to act. */
  public static final Duration EXPIRY_WARNING = Duration.ofSeconds(30);

  private final OrderBook book;
  private final EventSink events;

  private final Map<RequestId, QuoteRequest> requests = new LinkedHashMap<>();
  private final Map<RequestId, Solicitation> solicitations = new HashMap<>();
  private final Map<QuoteId, Quote> quotes = new LinkedHashMap<>();
  private final Map<RequestId, Counter> counters = new HashMap<>();
  private final Map<OrderId, Resting> restingBy = new HashMap<>();
  private final Map<RequestId, Map<ParticipantId, TopOfBook>> toldSoFar = new HashMap<>();
  private final List<PendingDispatch> pending = new ArrayList<>();
  private final java.util.Set<CounterId> degradedCounters = new java.util.HashSet<>();

  /** What a resting order belongs to, so a fill can be attributed without asking the book. */
  private sealed interface Resting {
    record OfQuote(QuoteId quote, Side side) implements Resting {}

    record OfCounter(CounterId counter, RequestId request) implements Resting {}

    /** An order the taker placed to take a price, rather than to show one. */
    record OfTakerOrder(RequestId request) implements Resting {}
  }

  /** A counter send that is due later. Re-checked when it fires, never trusted from when queued. */
  private record PendingDispatch(RequestId request, CounterId counter, ParticipantId maker,
      Instant dueAt) {}

  public NegotiationService(OrderBook book, EventSink events) {
    this.book = java.util.Objects.requireNonNull(book, "book");
    this.events = java.util.Objects.requireNonNull(events, "events");
  }

  // ---- The taker's side ----------------------------------------------------------------------

  /**
   * A taker asks for a price — instrument and size, and no side.
   *
   * <p>Two things happen and they are different objects. The taker is shown what the book already
   * holds, which adds no liquidity and exists to set the baseline every later improvement is
   * measured against. And the makers are solicited on a clock of the system's own, so the taker's
   * interest can later end without cutting the solicitation short.
   */
  public void requestQuote(
      RequestId id,
      ParticipantId taker,
      Instrument instrument,
      Quantity size,
      Instant expiresAt,
      Set<ParticipantId> makers,
      Instant solicitationExpiresAt) {
    if (requests.containsKey(id)) {
      throw new IllegalArgumentException("request " + id + " already exists");
    }
    QuoteRequest request = new QuoteRequest(id, taker, instrument, size, expiresAt);
    requests.put(id, request);
    solicitations.put(id, new Solicitation(id, makers, solicitationExpiresAt));

    TopOfBook top = book.topOfBook(instrument);
    baselines(id).put(taker, top);
    events.publish(new NegotiationEvent.QuoteShown(taker, id, instrument, top));

    for (ParticipantId maker : makers) {
      baselines(id).put(maker, top);
      events.publish(new NegotiationEvent.Solicited(maker, id, instrument, size));
    }
  }

  /**
   * The taker counters — one-sided, which is where they show their direction and where they commit.
   *
   * <p>The counter rests in the book, so any maker may take it, solicited or not. A previous
   * counter from the same taker is superseded: its order comes off, because a taker showing two
   * prices at once on the same request is offering something they did not mean to.
   */
  public CounterId counter(
      CounterId id,
      RequestId requestId,
      Side side,
      Price price,
      Quantity size,
      Instant expiresAt,
      Instant now) {
    QuoteRequest request = liveRequest(requestId);

    Counter previous = counters.get(requestId);
    if (previous != null && previous.isLive()) {
      book.cancel(previous.orderId());
      restingBy.remove(previous.orderId());
      previous.superseded();
    }

    OrderId orderId =
        book.place(
            request.instrument(),
            request.taker(),
            side,
            price,
            size,
            TimeInForce.GOOD_TIL_TIME,
            expiresAt);
    Counter counter =
        new Counter(id, requestId, request.taker(), side, price, size, orderId, expiresAt);
    counters.put(requestId, counter);
    restingBy.put(orderId, new Resting.OfCounter(id, requestId));

    disseminate(counter, now);
    return id;
  }

  /**
   * The taker takes the price they were shown.
   *
   * <p>Placed <b>all-or-nothing</b>, which is what a shown price is worth. A taker quoted a size
   * built from several orders is holding something that depends on all of them, so a plain limit
   * would turn one of those leaving into a partial fill and a surprise about the rest. Fill-or-kill
   * makes the worst outcome "no", which a taker can act on — and it is why the price a taker
   * accepts and the size they get are the same thing or neither.
   *
   * <p>Nothing rests: an accept that does not trade leaves the book exactly as it found it. If the
   * price has already gone, the taker learns it from the degradation they would have received
   * anyway, and no rejection path is needed here at all.
   *
   * @return the order id, so the caller can attribute the fill if one comes
   */
  public OrderId accept(RequestId requestId, Side side, Price limit, Quantity size) {
    QuoteRequest request = liveRequest(requestId);
    OrderId orderId =
        book.place(
            request.instrument(),
            request.taker(),
            side,
            limit,
            size,
            TimeInForce.FILL_OR_KILL,
            request.expiresAt());
    restingBy.put(orderId, new Resting.OfTakerOrder(requestId));
    return orderId;
  }

  /**
   * Moves the request's deadline out.
   *
   * <p>Published rather than adjusted quietly: a clock moving is a fact the taker acts on, and a
   * timer changed in silence is one nobody can reconcile against what they were told. It also
   * re-arms the expiry warning, because a request that was about to lapse and now is not should be
   * warned about again when it next approaches.
   */
  public void resetLifespan(RequestId requestId, Instant newExpiry) {
    QuoteRequest request = liveRequest(requestId);
    request.resetLifespan(newExpiry);
    events.publish(new NegotiationEvent.RequestLifespanReset(request.taker(), requestId));
  }

  /** The taker withdraws. Makers keep their quotes; only the enquiry goes. */
  public void cancelRequest(RequestId requestId) {
    QuoteRequest request = liveRequest(requestId);
    withdrawCounter(requestId);
    request.cancelled();
    events.publish(new NegotiationEvent.RequestCancelled(request.taker(), requestId));
    tellMakersInterestGone(requestId);
  }

  // ---- The makers' side ----------------------------------------------------------------------

  /**
   * A maker quotes, two-way, and both legs rest in the book.
   *
   * <p>The created event going back is the acknowledgement: a maker learns their price is live from
   * the event that says it exists, and a refused quote gets a rejection instead. One of the two
   * always arrives, so nobody infers from silence whether their price is working.
   *
   * @return the quote's id, or empty when it was rejected — the maker has been told which
   */
  public Optional<QuoteId> quote(
      QuoteId id,
      RequestId requestId,
      ParticipantId maker,
      Price bid,
      Price offer,
      Quantity size,
      Instant expiresAt) {
    QuoteRequest request = liveRequest(requestId);
    if (quotes.containsKey(id)) {
      throw new IllegalArgumentException("quote " + id + " already exists");
    }
    // A crossed quote is a maker offering to buy above their own selling price, which is a client
    // defect rather than an aggressive price. It comes back as a rejection because that is what the
    // maker needs — the other half of the acknowledgement, so nobody infers from silence.
    if (!bid.betterThan(offer, Side.OFFER)) {
      events.publish(
          new NegotiationEvent.QuoteRejected(
              maker, requestId, "bid " + bid + " is not below offer " + offer));
      return Optional.empty();
    }

    OrderId bidOrder =
        book.place(
            request.instrument(), maker, Side.BID, bid, size, TimeInForce.GOOD_TIL_TIME, expiresAt);
    OrderId offerOrder =
        book.place(
            request.instrument(), maker, Side.OFFER, offer, size, TimeInForce.GOOD_TIL_TIME, expiresAt);

    Quote quote =
        new Quote(
            id,
            requestId,
            maker,
            request.instrument(),
            new QuoteLeg(bidOrder, Side.BID, bid, size),
            new QuoteLeg(offerOrder, Side.OFFER, offer, size),
            expiresAt);
    quotes.put(id, quote);
    restingBy.put(bidOrder, new Resting.OfQuote(id, Side.BID));
    restingBy.put(offerOrder, new Resting.OfQuote(id, Side.OFFER));

    Optional.ofNullable(solicitations.get(requestId)).ifPresent(s -> s.quoted(maker));
    events.publish(new NegotiationEvent.QuoteCreated(maker, requestId, id));
    return Optional.of(id);
  }

  /** A maker refuses to price this one. Not a rejected quote — there is no quote. */
  public void decline(RequestId requestId, ParticipantId maker) {
    QuoteRequest request = liveRequest(requestId);
    Solicitation solicitation = solicitations.get(requestId);
    if (solicitation == null) {
      throw new IllegalArgumentException("no solicitation for " + requestId);
    }
    if (solicitation.declined(maker)) {
      // Nobody is left to wait for, and saying so beats running the clock out on an empty room.
      // The request is deliberately left live: a maker who passed now may quote in a minute.
      events.publish(new NegotiationEvent.AllMakersDeclined(request.taker(), requestId));
    }
  }

  /** The maker pulls one quote. Both legs go, because both were the same offer. */
  public void cancelQuote(QuoteId quoteId) {
    Quote quote = quotes.get(quoteId);
    if (quote == null || !quote.isLive()) {
      return;
    }
    quote.liveLegs().forEach(leg -> {
      book.cancel(leg.orderId());
      restingBy.remove(leg.orderId());
    });
    quote.cancelled();
    events.publish(
        new NegotiationEvent.QuoteCancelled(quote.maker(), quote.request(), quoteId));
  }

  /**
   * The maker pulls everything.
   *
   * <p>A kill switch, and the case that needs it most is the one where the maker cannot reliably
   * send a hundred messages — connectivity lost, a model gone wrong, stepping away. Per-quote
   * cancellation is not a substitute for it.
   *
   * @return how many quotes were pulled
   */
  public int cancelAllQuotesOf(ParticipantId maker) {
    List<QuoteId> mine =
        quotes.values().stream()
            .filter(quote -> quote.isLive() && quote.maker().equals(maker))
            .map(Quote::id)
            .toList();
    mine.forEach(this::cancelQuote);
    return mine.size();
  }

  // ---- What the book tells us ------------------------------------------------------------------

  /**
   * A resting order filled.
   *
   * <p>The order is attributed to whatever placed it, which is why this service keeps an index: by
   * the time a fill is reported the order may be gone from the book, and the party who has to be
   * told cannot be recovered from it.
   */
  public void filled(OrderId orderId, Quantity amount, Price price, Instant now) {
    Resting resting = restingBy.get(orderId);
    if (resting == null) {
      return; // Not ours. Someone else's liquidity traded, which is the book working.
    }
    switch (resting) {
      case Resting.OfQuote(QuoteId quoteId, Side side) -> quoteFilled(quoteId, side, amount, price);
      case Resting.OfCounter(CounterId counterId, RequestId requestId) ->
          counterFilled(counterId, requestId, orderId, amount, price, now);
      case Resting.OfTakerOrder(RequestId requestId) -> {
        restingBy.remove(orderId);
        creditRequest(requestId, amount, price);
      }
    }
  }

  private void quoteFilled(QuoteId quoteId, Side side, Quantity amount, Price price) {
    Quote quote = quotes.get(quoteId);
    if (quote == null || !quote.isLive()) {
      return;
    }
    quote.filled(side, amount);
    events.publish(
        new NegotiationEvent.QuoteTraded(
            quote.maker(), quote.request(), quoteId, side, amount, price));
    quote.leg(side).ifPresentOrElse(leg -> {}, () -> forgetLeg(quoteId, side));
    // Deliberately does not touch the request's outstanding. A maker's quote lifted by a passing
    // third party is a trade in the book, not a fill for the taker who happened to prompt the
    // quote — crediting it would close a request the taker got nothing from.
  }

  private void counterFilled(
      CounterId counterId,
      RequestId requestId,
      OrderId orderId,
      Quantity amount,
      Price price,
      Instant now) {
    Counter counter = counters.get(requestId);
    if (counter == null || !counter.id().equals(counterId) || !counter.isLive()) {
      return;
    }
    counter.filled(amount);
    if (!counter.isLive()) {
      restingBy.remove(orderId);
    }
    creditRequest(requestId, amount, price);
  }

  /**
   * Applies a fill to the request's outstanding, which is the only thing that decides its ending.
   *
   * <p>Who filled it does not enter into it. A maker who was never solicited, seeing the taker's
   * counter resting in the book and hitting it, closes the request exactly as a solicited one
   * would — the taker asked for size, not for size from a particular party.
   */
  private void creditRequest(RequestId requestId, Quantity amount, Price price) {
    QuoteRequest request = requests.get(requestId);
    if (request == null || !request.isLive()) {
      return;
    }
    boolean closed = request.tradeOccurred(amount);
    events.publish(
        new NegotiationEvent.TradeOccurred(
            request.taker(), requestId, amount, price, request.outstanding()));
    if (closed) {
      withdrawCounter(requestId);
      events.publish(new NegotiationEvent.RequestClosed(request.taker(), requestId));
      tellMakersDoneAway(requestId);
    }
  }

  // ---- The poll ---------------------------------------------------------------------------------

  /**
   * Everything that happens because time passed.
   *
   * <p>A poll rather than a reaction to every book change, because comparing top of book on every
   * mutation is a firehose on an active instrument and most of those frames tell a participant
   * nothing they can act on. Between polls the book may move several times and the participant sees
   * the net result, which is what they would have acted on anyway. That is what makes one observer
   * affordable for every participant at once.
   *
   * <p>Order matters here. Expiries are applied before anything is compared, so a party is never
   * told about a top of book that includes liquidity which has already lapsed.
   */
  public void poll(Instant now) {
    expireQuotes(now);
    expireCounters(now);
    expireRequests(now);
    warnAboutExpiry(now);
    dispatchDue(now);
    publishMovements();
    publishCounterMovements();
  }

  private void expireQuotes(Instant now) {
    List.copyOf(quotes.values()).stream()
        .filter(quote -> quote.isLive() && quote.hasExpiredAt(now))
        .forEach(quote -> {
          quote.liveLegs().forEach(leg -> restingBy.remove(leg.orderId()));
          quote.expired();
          events.publish(
              new NegotiationEvent.QuoteExpired(quote.maker(), quote.request(), quote.id()));
        });
  }

  private void expireCounters(Instant now) {
    counters.values().stream()
        .filter(counter -> counter.isLive() && counter.hasExpiredAt(now))
        .forEach(counter -> {
          restingBy.remove(counter.orderId());
          counter.expired();
        });
  }

  private void expireRequests(Instant now) {
    List.copyOf(requests.values()).stream()
        .filter(request -> request.isLive() && request.hasExpiredAt(now))
        .forEach(request -> {
          withdrawCounter(request.id());
          request.expired();
          events.publish(
              new NegotiationEvent.RequestExpired(request.taker(), request.id()));
          tellMakersInterestGone(request.id());
        });
  }

  private void warnAboutExpiry(Instant now) {
    requests.values().stream()
        .filter(request -> request.shouldWarnAt(now, EXPIRY_WARNING))
        .forEach(request ->
            events.publish(
                new NegotiationEvent.RequestExpiring(request.taker(), request.id())));
  }

  /**
   * Sends the counter on to the makers whose head start has elapsed.
   *
   * <p><b>Re-checked here, not when it was queued.</b> The counter can be pulled, improved or
   * filled inside the delay window; a fire-and-forget send would tell a maker about a counter that
   * no longer exists, and one of them may act on it.
   */
  private void dispatchDue(Instant now) {
    List<PendingDispatch> due =
        pending.stream().filter(dispatch -> !now.isBefore(dispatch.dueAt())).toList();
    pending.removeAll(due);
    for (PendingDispatch dispatch : due) {
      Counter counter = counters.get(dispatch.request());
      if (counter == null
          || !counter.isLive()
          || !counter.id().equals(dispatch.counter())) {
        continue;
      }
      tellAboutCounter(dispatch.maker(), counter);
    }
  }

  /**
   * Compares each party's baseline against the book and tells them what moved.
   *
   * <p>The comparison never asks <em>why</em> it moved. An order expiring, being hit, being
   * cancelled, or being replaced by a worse one all reach a participant as the same fact — which is
   * the point: watch for a replace and you miss the other three.
   */
  /**
   * Tells the makers when the counter they were shown is no longer the best on its side.
   *
   * <p>The mirror of the taker's degradation, and it matters for the same reason: a maker weighing
   * whether to take a counter needs to know it has stopped being the thing worth taking. Sent once
   * per counter, because a maker told every second that a price is still not the best learns
   * nothing after the first time.
   */
  private void publishCounterMovements() {
    for (Counter counter : counters.values()) {
      if (!counter.isLive() || degradedCounters.contains(counter.id())) {
        continue;
      }
      QuoteRequest request = requests.get(counter.request());
      if (request == null || !request.isLive()) {
        continue;
      }
      TopOfBook top = book.topOfBook(request.instrument());
      boolean bettered =
          top.side(counter.side())
              .map(best -> best.betterThan(counter.price(), counter.side()))
              .orElse(false);
      if (!bettered) {
        continue;
      }
      degradedCounters.add(counter.id());
      Solicitation solicitation = solicitations.get(counter.request());
      if (solicitation != null) {
        solicitation
            .makers()
            .forEach(
                maker ->
                    events.publish(
                        new NegotiationEvent.CounterDegraded(
                            maker, counter.request(), counter.id(), counter.side())));
      }
    }
  }

  private void publishMovements() {
    for (QuoteRequest request : requests.values()) {
      if (!request.isLive()) {
        continue;
      }
      TopOfBook now = book.topOfBook(request.instrument());
      Map<ParticipantId, TopOfBook> told = baselines(request.id());
      for (Map.Entry<ParticipantId, TopOfBook> entry : told.entrySet()) {
        ParticipantId who = entry.getKey();
        TopOfBook was = entry.getValue();
        for (Side side : Side.values()) {
          switch (now.movementSince(was, side)) {
            case IMPROVED -> events.publish(
                new NegotiationEvent.QuoteImproved(who, request.id(), side, now));
            case DEGRADED -> events.publish(
                new NegotiationEvent.QuoteDegraded(who, request.id(), side, now));
            case UNCHANGED -> { }
          }
        }
        entry.setValue(now);
      }
    }
  }

  // ---- Helpers ------------------------------------------------------------------------------

  /**
   * Tells the best-priced maker at once, and queues the rest.
   *
   * <p>Which maker is best is read from the book rather than remembered, because by the time a
   * taker counters the best price may belong to someone who quoted after the one this service last
   * thought was winning.
   */
  private void disseminate(Counter counter, Instant now) {
    Solicitation solicitation = solicitations.get(counter.request());
    if (solicitation == null) {
      return;
    }
    Optional<ParticipantId> best = bestMakerOn(counter.request(), counter.side().opposite());
    best.ifPresent(maker -> tellAboutCounter(maker, counter));

    Instant dueAt = now.plus(COUNTER_HEAD_START);
    solicitation.makers().stream()
        .filter(maker -> best.isEmpty() || !maker.equals(best.get()))
        .forEach(maker ->
            pending.add(
                new PendingDispatch(counter.request(), counter.id(), maker, dueAt)));
  }

  private void tellAboutCounter(ParticipantId maker, Counter counter) {
    counter.remaining().ifPresent(size ->
        events.publish(
            new NegotiationEvent.CounterImproved(
                maker, counter.request(), counter.id(), counter.side(), counter.price(), size)));
  }

  /** Whoever is quoting the best price on this side of this request's instrument. */
  private Optional<ParticipantId> bestMakerOn(RequestId requestId, Side side) {
    return quotes.values().stream()
        .filter(quote -> quote.isLive() && quote.request().equals(requestId))
        .flatMap(quote -> quote.leg(side).map(leg -> Map.entry(quote.maker(), leg)).stream())
        .min((a, b) -> a.getValue().price().betterThan(b.getValue().price(), side) ? -1 : 1)
        .map(Map.Entry::getKey);
  }

  private void withdrawCounter(RequestId requestId) {
    Counter counter = counters.get(requestId);
    if (counter != null && counter.isLive()) {
      book.cancel(counter.orderId());
      restingBy.remove(counter.orderId());
      counter.cancelled();
    }
  }

  private void tellMakersInterestGone(RequestId requestId) {
    Solicitation solicitation = solicitations.get(requestId);
    if (solicitation == null || !solicitation.markInterestGone()) {
      return;
    }
    solicitation.makers().forEach(maker ->
        events.publish(new NegotiationEvent.InterestGone(maker, requestId)));
  }

  /** Everyone who quoted and did not get the trade hears that somebody else did. */
  private void tellMakersDoneAway(RequestId requestId) {
    quotes.values().stream()
        .filter(quote -> quote.request().equals(requestId))
        .map(Quote::maker)
        .distinct()
        .forEach(maker -> events.publish(new NegotiationEvent.DoneAway(maker, requestId)));
  }

  private void forgetLeg(QuoteId quoteId, Side side) {
    restingBy.entrySet()
        .removeIf(entry ->
            entry.getValue() instanceof Resting.OfQuote(QuoteId id, Side legSide)
                && id.equals(quoteId)
                && legSide == side);
  }

  private Map<ParticipantId, TopOfBook> baselines(RequestId requestId) {
    return toldSoFar.computeIfAbsent(requestId, key -> new LinkedHashMap<>());
  }

  private QuoteRequest liveRequest(RequestId requestId) {
    QuoteRequest request = requests.get(requestId);
    if (request == null) {
      throw new IllegalArgumentException("no request " + requestId);
    }
    if (!request.isLive()) {
      throw new IllegalStateException(
          "request " + requestId + " already ended as " + request.terminal().orElseThrow());
    }
    return request;
  }

  // ---- Reading the state, for callers and tests ------------------------------------------------

  public Optional<QuoteRequest> request(RequestId id) {
    return Optional.ofNullable(requests.get(id));
  }

  public Optional<Quote> quote(QuoteId id) {
    return Optional.ofNullable(quotes.get(id));
  }

  public Optional<Counter> counterFor(RequestId id) {
    return Optional.ofNullable(counters.get(id));
  }

  public Optional<Solicitation> solicitation(RequestId id) {
    return Optional.ofNullable(solicitations.get(id));
  }
}
