package com.damianhoward.rfq.model;

/**
 * Who an order, quote, request or counter belongs to — a taker or a maker.
 *
 * <p>There is deliberately no value meaning "nobody". A venue always knows whose order it is,
 * because that is who the fill books to; a market is anonymous when counterparties cannot see each
 * other, which is a property of what gets published rather than of whether an owner exists.
 *
 * @param id the participant's identifier, never blank
 */
public record ParticipantId(String id) {

  public ParticipantId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("participant id must not be blank");
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
