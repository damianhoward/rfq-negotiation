package com.damianhoward.rfq.model;

/**
 * What is being negotiated.
 *
 * @param symbol the instrument's symbol, never blank
 */
public record Instrument(String symbol) {

  public Instrument {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("instrument symbol must not be blank");
    }
  }

  @Override
  public String toString() {
    return symbol;
  }
}
