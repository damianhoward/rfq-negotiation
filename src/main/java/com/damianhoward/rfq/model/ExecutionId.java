package com.damianhoward.rfq.model;

/**
 * The venue's identifier for one execution.
 *
 * <p>Distinct from the order it filled, because an order fills more than once and the same fill
 * arrives more than once. A transport that redelivers is the normal case rather than the failure
 * case, so applying a fill has to be idempotent, and an order id cannot make it so: two genuine
 * partial fills of 400 on one order are indistinguishable from one fill of 400 delivered twice.
 *
 * @param id the venue's execution identifier, never blank
 */
public record ExecutionId(String id) {

  public ExecutionId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("execution id must not be blank");
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
