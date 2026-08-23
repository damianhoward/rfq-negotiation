package com.damianhoward.rfq.model;

/** Identifies a taker's request. Also correlates the solicitation sent out on its behalf. */
public record RequestId(String id) {
  public RequestId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("request id must not be blank");
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
