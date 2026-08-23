package com.damianhoward.rfq.model;

/**
 * One side of a maker's two-way quote, once it is resting in the book.
 *
 * <p>A leg is a real order with a real identifier, which is what lets a fill be attributed and a
 * quote be cancelled. The two legs of a quote are placed and cancelled together and share a
 * deadline, but they are otherwise independent: one may trade while the other keeps working, and
 * that is the spread the maker came to earn.
 *
 * @param orderId the book's identifier for this leg
 * @param side which side of the market it rests on
 * @param price the price quoted
 * @param remaining what is still working, after any partial fills
 */
public record QuoteLeg(OrderId orderId, Side side, Price price, Quantity remaining) {

  public QuoteLeg {
    java.util.Objects.requireNonNull(orderId, "orderId");
    java.util.Objects.requireNonNull(side, "side");
    java.util.Objects.requireNonNull(price, "price");
    java.util.Objects.requireNonNull(remaining, "remaining");
  }

  /** This leg after {@code filled} has traded, or empty once nothing is left working. */
  public java.util.Optional<QuoteLeg> less(Quantity filled) {
    return remaining.minus(filled).map(left -> new QuoteLeg(orderId, side, price, left));
  }
}
