package com.damianhoward.rfq.port;

import com.damianhoward.rfq.event.NegotiationEvent;

/**
 * Where outbound events go.
 *
 * <p>One method, because the negotiation has no opinion about transport: the same event reaches a
 * FIX session, a websocket or a test list without this module knowing which. That is what lets a
 * protocol gateway be written later without the core learning a single tag.
 */
@FunctionalInterface
public interface EventSink {
  void publish(NegotiationEvent event);
}
