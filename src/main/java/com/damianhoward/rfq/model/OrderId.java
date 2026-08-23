package com.damianhoward.rfq.model;

/**
 * The book's identifier for a resting order. Minted by the book, held by this service so that a
 * fill can be traced back to the quote or counter that placed it, and so that either can be
 * cancelled.
 *
 * @param id the book's order identifier
 */
public record OrderId(long id) {
  @Override
  public String toString() {
    return Long.toString(id);
  }
}
