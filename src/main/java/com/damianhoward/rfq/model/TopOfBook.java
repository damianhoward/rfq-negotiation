package com.damianhoward.rfq.model;

import java.util.Optional;

/**
 * The best bid and best offer available right now — the quote a taker is shown.
 *
 * <p><b>This belongs to nobody.</b> Its two sides need not come from the same maker, and with
 * aggregation either side may be several makers at once. It creates no order and adds no
 * liquidity: it is the book, read. Which makes the two sides independent in a way a maker's
 * {@link Quote} never is — one can degrade while the other does not, because the orders behind
 * them are unrelated, and there is nothing here to cancel because there is nothing here.
 *
 * <p>Either side may be absent when the book is one-sided.
 *
 * @param bid the best bid, or empty
 * @param offer the best offer, or empty
 */
public record TopOfBook(Optional<Price> bid, Optional<Price> offer) {

  public static final TopOfBook EMPTY = new TopOfBook(Optional.empty(), Optional.empty());

  public TopOfBook {
    java.util.Objects.requireNonNull(bid, "bid");
    java.util.Objects.requireNonNull(offer, "offer");
  }

  public static TopOfBook of(Price bid, Price offer) {
    return new TopOfBook(Optional.ofNullable(bid), Optional.ofNullable(offer));
  }

  public Optional<Price> side(Side side) {
    return side == Side.BID ? bid : offer;
  }

  /**
   * How this top compares with {@code previous} on one side: better, worse, or neither.
   *
   * <p>A side appearing where there was none is an improvement, and a side vanishing is a
   * degradation — those are the cases a naive price comparison drops on the floor.
   */
  public Movement movementSince(TopOfBook previous, Side side) {
    Optional<Price> now = side(side);
    Optional<Price> was = previous.side(side);
    if (now.isEmpty() && was.isEmpty()) {
      return Movement.UNCHANGED;
    }
    if (now.isEmpty()) {
      return Movement.DEGRADED;
    }
    if (was.isEmpty()) {
      return Movement.IMPROVED;
    }
    if (now.get().equals(was.get())) {
      return Movement.UNCHANGED;
    }
    return now.get().betterThan(was.get(), side) ? Movement.IMPROVED : Movement.DEGRADED;
  }

  /** Which way a side moved between what a party was last told and what the book now holds. */
  public enum Movement {
    IMPROVED,
    DEGRADED,
    UNCHANGED
  }
}
