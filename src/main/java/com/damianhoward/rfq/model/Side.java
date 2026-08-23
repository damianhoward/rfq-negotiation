package com.damianhoward.rfq.model;

/**
 * The two sides of a market. {@link #BID} is the buying side, where a higher price is better;
 * {@link #OFFER} is the selling side, where a lower price is.
 */
public enum Side {
  BID,
  OFFER;

  public Side opposite() {
    return this == BID ? OFFER : BID;
  }
}
