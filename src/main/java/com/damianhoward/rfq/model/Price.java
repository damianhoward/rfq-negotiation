package com.damianhoward.rfq.model;

import java.math.BigDecimal;

/**
 * A price as an integer number of ticks at {@link #SCALE} decimal places.
 *
 * <p>Scaled longs rather than {@code double}: equal prices must be equal, and a price that arrived
 * as {@code 4100.00} has to compare equal to one computed as {@code 4100.00} however it got there.
 * {@link BigDecimal} is touched only when parsing or rendering.
 *
 * @param ticks the price in ticks, never negative
 */
public record Price(long ticks) implements Comparable<Price> {

  public static final int SCALE = 8;

  public Price {
    if (ticks < 0) {
      throw new IllegalArgumentException("price must not be negative, got " + ticks + " ticks");
    }
  }

  /**
   * Parses a decimal string such as {@code "4100.50"} into exact ticks.
   *
   * @throws ArithmeticException if the text carries more than {@link #SCALE} decimals, which would
   *     silently lose precision, or overflows a {@code long}
   */
  public static Price of(String text) {
    return new Price(new BigDecimal(text).movePointRight(SCALE).longValueExact());
  }

  /** True when this price is better than {@code other} for someone quoting on {@code side}. */
  public boolean betterThan(Price other, Side side) {
    return side == Side.BID ? ticks > other.ticks : ticks < other.ticks;
  }

  @Override
  public int compareTo(Price other) {
    return Long.compare(ticks, other.ticks);
  }

  @Override
  public String toString() {
    return BigDecimal.valueOf(ticks, SCALE).stripTrailingZeros().toPlainString();
  }
}
