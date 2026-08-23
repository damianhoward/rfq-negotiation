package com.damianhoward.rfq.model;

/**
 * A size. Always positive: a zero or negative quantity is not a smaller order, it is a caller
 * error, and admitting one would let it travel a long way before anything noticed.
 *
 * @param units the size, always greater than zero
 */
public record Quantity(long units) implements Comparable<Quantity> {

  public Quantity {
    if (units <= 0) {
      throw new IllegalArgumentException("quantity must be positive, got " + units);
    }
  }

  public static Quantity of(long units) {
    return new Quantity(units);
  }

  /** This quantity less {@code taken}, or empty when nothing remains. */
  public java.util.Optional<Quantity> minus(Quantity taken) {
    long left = units - taken.units;
    return left > 0 ? java.util.Optional.of(new Quantity(left)) : java.util.Optional.empty();
  }

  public Quantity min(Quantity other) {
    return units <= other.units ? this : other;
  }

  @Override
  public int compareTo(Quantity other) {
    return Long.compare(units, other.units);
  }

  @Override
  public String toString() {
    return Long.toString(units);
  }
}
