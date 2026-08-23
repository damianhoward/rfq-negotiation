package com.damianhoward.rfq.model;

/** Identifies one maker's quote. */
public record QuoteId(String id) {
  public QuoteId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("quote id must not be blank");
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
