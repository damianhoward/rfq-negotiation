package com.damianhoward.rfq.model;

/**
 * How long an order is allowed to work, and what happens to the part that does not fill.
 *
 * <p>The negotiation needs all three and for different reasons, so the port carries it rather than
 * leaving an adapter to guess: a quote has to rest, a hit must not, and an accepted price has to be
 * all-or-nothing.
 */
public enum TimeInForce {
  /** Rests until it fills, is cancelled, or reaches its deadline. Quotes and counters. */
  GOOD_TIL_TIME,

  /**
   * Fills what it can immediately and discards the rest.
   *
   * <p>What a hit should be, so that a thin book cannot leave a taker resting at a price they chose
   * for an execution they expected to be complete.
   */
  IMMEDIATE_OR_CANCEL,

  /**
   * Fills completely or not at all, leaving the book untouched when it cannot.
   *
   * <p>What an accepted price needs. A taker shown a price built from several orders is holding
   * something that depends on all of them, so one leaving would otherwise turn an accept into a
   * partial fill and a surprise about the rest. All-or-nothing makes the worst outcome "no", which
   * is a thing a taker can act on.
   */
  FILL_OR_KILL
}
