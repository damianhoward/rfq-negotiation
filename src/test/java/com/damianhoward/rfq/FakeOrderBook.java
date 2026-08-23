package com.damianhoward.rfq;

import com.damianhoward.rfq.model.Instrument;
import com.damianhoward.rfq.model.OrderId;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.model.Price;
import com.damianhoward.rfq.model.Quantity;
import com.damianhoward.rfq.model.Side;
import com.damianhoward.rfq.model.TimeInForce;
import com.damianhoward.rfq.model.TopOfBook;
import com.damianhoward.rfq.port.OrderBook;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A book good enough to negotiate against.
 *
 * <p>It rests orders, cancels them, expires them and reports a top — which is the whole of the port
 * — and it does <em>not</em> match, because the negotiation service never asks it to. Fills reach
 * the service through {@code filled(..)} the way a real book's egress would, so a test says what
 * traded rather than constructing a crossing order and hoping.
 *
 * <p>Self-match prevention lives in the real book and is tested there. What matters here is that
 * this fake never invents liquidity the service did not place.
 */
final class FakeOrderBook implements OrderBook {

  private record Resting(
      OrderId id,
      Instrument instrument,
      ParticipantId owner,
      Side side,
      Price price,
      Quantity size,
      TimeInForce timeInForce,
      Instant goodTill) {}

  private final AtomicLong nextId = new AtomicLong(1);
  private final List<Resting> resting = new ArrayList<>();
  private final List<Resting> placed = new ArrayList<>();
  private Instant now = Instant.EPOCH;

  /** Moves the fake's clock, which is what decides whether a resting order still counts. */
  void clockAt(Instant instant) {
    this.now = instant;
  }

  @Override
  public OrderId place(
      Instrument instrument,
      ParticipantId owner,
      Side side,
      Price price,
      Quantity size,
      TimeInForce timeInForce,
      Instant goodTill) {
    OrderId id = new OrderId(nextId.getAndIncrement());
    Resting order = new Resting(id, instrument, owner, side, price, size, timeInForce, goodTill);
    resting.add(order);
    placed.add(order);
    // Only a resting order joins the book. An immediate or all-or-nothing order either trades on
    // arrival or is gone, so a fake that left one lying about would show liquidity a real book
    // never had.
    if (timeInForce != TimeInForce.GOOD_TIL_TIME) {
      resting.removeIf(candidate -> candidate.id().equals(id));
    }
    return id;
  }

  /** What time in force an order was placed with, so a test can assert the service chose right. */
  Optional<TimeInForce> timeInForceOf(OrderId orderId) {
    return placed.stream()
        .filter(order -> order.id().equals(orderId))
        .findFirst()
        .map(Resting::timeInForce);
  }

  @Override
  public boolean cancel(OrderId orderId) {
    return resting.removeIf(order -> order.id().equals(orderId));
  }

  @Override
  public TopOfBook topOfBook(Instrument instrument) {
    return new TopOfBook(best(instrument, Side.BID), best(instrument, Side.OFFER));
  }

  private Optional<Price> best(Instrument instrument, Side side) {
    Comparator<Resting> byPrice =
        side == Side.BID
            ? Comparator.comparing(Resting::price).reversed()
            : Comparator.comparing(Resting::price);
    return resting.stream()
        .filter(order -> order.instrument().equals(instrument))
        .filter(order -> order.side() == side)
        .filter(order -> !now.isAfter(order.goodTill()))
        .min(byPrice)
        .map(Resting::price);
  }

  /** Takes an order off the book as a fill would, so a test can then report that fill. */
  void consume(OrderId orderId) {
    cancel(orderId);
  }

  /** How many orders are still resting — the check that a cancellation actually reached the book. */
  int restingCount() {
    return (int) resting.stream().filter(order -> !now.isAfter(order.goodTill())).count();
  }

  boolean isResting(OrderId orderId) {
    return resting.stream()
        .anyMatch(order -> order.id().equals(orderId) && !now.isAfter(order.goodTill()));
  }

  Optional<ParticipantId> ownerOf(OrderId orderId) {
    return resting.stream()
        .filter(order -> order.id().equals(orderId))
        .findFirst()
        .map(Resting::owner);
  }
}
