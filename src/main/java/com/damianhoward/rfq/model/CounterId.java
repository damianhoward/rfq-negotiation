package com.damianhoward.rfq.model;

/** Identifies a taker's counter. */
public record CounterId(String id) {
  public CounterId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("counter id must not be blank");
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
